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

### Federation entity dispatch grouping

A sixth `getTenantId` call site lives outside the DataLoader-name family: `HandleMethodBody.java:131` emits `String tenantId = graphitronContext(repEnv).getTenantId(repEnv)` and uses that string as the inner key of a `Map<Integer, Map<String, List<Object[]>>>` grouping structure (outer key = alternative index, inner key = tenantId) that the generated federation `_entities` handler builds at decode time and iterates at dispatch time. Removing `getTenantId` from the sealed interface cascades into this site: the call deletes, the inner-tenant level of the grouping collapses, and the dispatch loop drops its inner-map iteration. The runtime behaviour is unchanged for single-tenant apps — today's `""` tenantId would have produced a single-entry inner map per alternative anyway, so collapsing the level is the same dispatch shape with less plumbing. R45 reintroduces the per-tenant grouping when `<tenantColumn>` is configured, restoring the inner-map level.

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
- The classified map is stored on the build result and read by `GraphitronFacadeGenerator` and `GraphitronContextInterfaceGenerator`. Rejections from the new classifier check land on the same build-result surface; `GraphitronSchemaValidator.validate` (`GraphitronSchemaValidator.java:34-42`) gains a new cross-cutting check `validateContextArgumentTypeAgreement(schema, errors)` that drains the rejection list into `ValidationError`s, mirroring `validateLocalContextErrorsFieldGuards(schema, errors)` at line 40. This closes the validator-mirrors-classifier loop: the same `Rejection.contextArgumentTypeConflict` the classifier produces surfaces as a build-time error before the factory emitter is ever asked to paste a non-existent `TypeName`.

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

### Federation entity dispatch grouping (`HandleMethodBody.java`)

- Delete the `String tenantId = graphitronContext(repEnv).getTenantId(repEnv)` line at `:131`.
- Collapse `emitGroupingMaps` (`HandleMethodBody.java:62-68`): change the `groups` declaration from `Map<Integer, Map<String, List<Object[]>>>` to `Map<Integer, List<Object[]>>`; the inner `Map<String, List<Object[]>>` type alias deletes with it.
- Collapse the per-rep grouping call (`HandleMethodBody.java:132-135`): change `groups.computeIfAbsent(altIndex, k -> new LinkedHashMap<>()).computeIfAbsent(tenantId, k -> new ArrayList<>()).add(new Object[]{idx, cols, repEnv})` to `groups.computeIfAbsent(altIndex, k -> new ArrayList<>()).add(new Object[]{idx, cols, repEnv})`.
- Collapse `emitGroupDispatch` (`HandleMethodBody.java:138-167`): drop the inner `for (innerEntry : altEntry.getValue().entrySet())` loop and the inner-entry parameterised type setup; read `List<Object[]> bindings = altEntry.getValue()` directly off the outer entry. The downstream `Object[] first = bindings.get(0)`, `groupEnv = (DataFetchingEnvironment) first[2]`, and `dsl = graphitronContext(groupEnv).getDslContext(groupEnv)` lines stay shaped as-is.
- Update the class-level javadoc at `HandleMethodBody.java:13-33` — the `<ol>` step list mentions `getTenantId(repEnv)` per-rep resolution at `<li>2</li>` and `(alternative-index, tenantId)` grouping at `<li>4</li>`. Both bullets reshape: step 2 keeps the per-rep DFE construction (the `repEnv` still carries `arguments(rep)` for any in-rep argument reads even though the consumer no longer reads `getTenantId(repEnv)`), but the `getTenantId(repEnv)` mention deletes; step 4 reshapes to "Group bindings by alternative-index into a `LinkedHashMap`".
- Sister doc comments at `EntityFetcherDispatchClassGenerator.java:42` and `QueryNodeFetcherClassGenerator.java:130` reference the per-rep DFE construction in terms of `getTenantId(repEnv)` resolution; both rewrite to drop the `getTenantId` mention. The per-rep DFE construction itself stays in both classes (it carries `arguments(rep)` for in-rep argument reads).

### `graphitron-sakila-example` consumer migration

The example module is the canonical consumer-test reference (per R67 changelog: "doubles as the runnable reference application *and* the recommended consumer test pattern"), so its code is the first non-doc client of the new design at compile time. Sealing `GraphitronContext` and removing the `(GraphitronContext)` factory overload turn ~17 sites into compile errors; the L5 compile gate (`mvn compile -pl :graphitron-sakila-example -Plocal-db`) cannot pass without migrating them in the same commit window as the generator change. Per-site treatment:

- *Anonymous-impl test sites (14 sites across 13 files, single-method override pattern).* Each block today reads:
+
[source,java]
----
var context = new GraphitronContext() {
    @Override public DSLContext getDslContext(DataFetchingEnvironment env) { return dsl; }
    @Override public <T> T getContextArgument(DataFetchingEnvironment env, String name) { return null; }
};
var input = ExecutionInput.newExecutionInput()
    .query(query)
    .graphQLContext(b -> b.put(GraphitronContext.class, context))
    .dataLoaderRegistry(new DataLoaderRegistry())
    .build();
----
+
Migrate to `var input = Graphitron.newExecutionInput(dsl).query(query).build();` — the anonymous impl deletes, the per-test helper holds the `DSLContext` directly, and the `DataLoaderRegistry` wiring comes from the factory. Files: `querydb/SingleRecordTableFieldServiceProducerExecutionTest.java:68`, `querydb/CompositeKeyLookupQueryTest.java:83`, `querydb/SingleRecordPayloadDmlTest.java:69`, `querydb/DmlBulkMutationsExecutionTest.java:85`, `querydb/PolymorphicProjectionQueryTest.java:86`, `querydb/MultiSchemaQueryTest.java:74`, `querydb/MatchQueryExampleTest.java:82`, `querydb/ApprovalQueryExampleTest.java:99`, `querydb/GraphQLQueryTest.java:90` (and the sibling `executeRaw` block at `:119`), `querydb/FederationEntitiesDispatchTest.java:70`, `internal/AccessorDerivedBatchKeyTest.java:88`, `internal/MutationPayloadLifterTest.java:89`, `internal/AddressOccupantsListBatchingTest.java:93`.

- *`executeWithContext` helper (`querydb/GraphQLQueryTest.java:138-149`).* The helper takes a `GraphitronContext` parameter; under R190 callers can't supply their own impl. Either delete it (collapsing remaining callers to the bare `execute` helper) or reshape its signature to take a `DSLContext` (`executeWithContext(String query, DSLContext perRequestDsl)`) and forward into `Graphitron.newExecutionInput(perRequestDsl, ...)`. Pick the second shape if any call site after the `getTenantId`-using tests have been commented out still needs per-request `DSLContext` variation; otherwise the first.

- *Per-tenant `getTenantId`-override tests (2 sites).* Both blocks override `getTenantId` directly, exercising the multi-tenant DataLoader partitioning that R190 deliberately removes:
  * `querydb/FederationEntitiesDispatchTest.java:256-287` — test method `entities_multiTenancyPartition_oneSelectPerTenant`.
  * `querydb/GraphQLQueryTest.java:2423-2454` — test method `nodes_perTenantPartition_separateBatchPerTenant`.
+
Comment out the entire test method (annotation through closing brace) with a one-line forward-reference comment: `// Commented out under R190: getTenantId override and per-tenant DataLoader partitioning are reintroduced under R45 (see graphitron-rewrite/roadmap/tenant-routing-and-execution-input.md).` Do not delete — the assertion shapes (`QUERY_COUNT == 2` for the partitioning case, the per-rep dispatch invariant for the federation case) are the canonical execution-tier proof R45 will re-anchor on. The comment leaves a discoverable handle for R45 to lift the gate and uncomment in one diff, with a stable test name to grep for.

- *Main `AppContext.java` (`graphitron-sakila-example/src/main/java/no/sikt/graphitron/sakila/example/app/AppContext.java`).* The class today is `public final class AppContext implements GraphitronContext` with a per-request `DSLContext` and a context-values map. Under R190 it is dead weight: the factory IS the per-request wiring, and the `contextValues` map collapses into typed factory parameters reflected from the schema's `contextArguments` list. Delete the file outright. The `GraphqlResource` migration below assumes the deletion.

- *`GraphqlResource.java` (`graphitron-sakila-example/src/main/java/no/sikt/graphitron/sakila/example/app/GraphqlResource.java`).* The resource today instantiates an `AppContext` per request and stashes it on `ExecutionInput.graphQLContext(b -> b.put(GraphitronContext.class, ctx))`. Migrate to `Graphitron.newExecutionInput(dsl, ...)` directly at request entry — `dsl` from the Quarkus-managed `AgroalDataSource`, plus any contextArgument values pulled from the JAX-RS request (JWT claims, header values) threaded through the factory's typed parameter list. If the current example schema declares no `contextArguments`, the factory collapses to `Graphitron.newExecutionInput(dsl)` and the migration is mechanical; if R190's L5 compile fixture adds a contextArgument (per "Compile (L5)" in `## Tests`), the resource grows a matching slot.

- *Module `README.md` (`graphitron-sakila-example/README.md:23`).* The `AppContext` bullet (line 23, currently describing `implements GraphitronContext` + the context-values map) deletes. Replace with one bullet pointing at `GraphqlResource`'s `Graphitron.newExecutionInput(...)` call as the runtime wiring site, and one bullet pointing at the schema's `contextArguments` list as the source of truth for which JAX-RS request values get threaded through.

A thirteenth site discovered during implementation that pattern-matches "consumer implements / instantiates `GraphitronContext` directly" folds into the same commit window with the same lens: would this code compile against the post-R190 generated `GraphitronContext`?

### Existing generator-test updates (`graphitron/src/test/`)

The generator unit tests that pin the pre-R190 shape will fail under the new factory, sealed interface, and removed `getTenantId`. Each block below names the test and the post-R190 outcome:

- `GraphitronContextInterfaceGeneratorTest.java:27-32` (`generatedInterface_hasFourMethods`): retitle to `generatedInterface_hasThreeMethods` and drop `"getTenantId"` from the `containsExactlyInAnyOrder` set.
- `GraphitronContextInterfaceGeneratorTest.java:73-80` (`generatedInterface_hasExactlyOneAbstractMethod`): delete. The load-bearing rationale (the `(GraphitronContext) env -> dsl` lambda form needs exactly one abstract method) disappears with the `newExecutionInput(DSLContext)` lambda overload R190 removes. The replacement invariant — sealed + permits a single generated impl — already lives on the new `GraphitronContextImpl` snapshot under the pipeline-tier coverage and needs no unit-tier proxy.
- `GraphitronContextInterfaceGeneratorTest.java:82-88` (`getTenantId_hasDefaultImplementationReturningEmptyString`): delete outright; the method no longer exists on the interface.
- `GraphitronContextInterfaceGeneratorTest.java:60-65` (`getContextArgument_hasDefaultImplementationReadingGraphQLContext`): rename and rewrite — the default body now reads `env.getGraphQlContext().get(name)` and applies `expectedType.cast(...)`. Assert the third parameter `(Class<T> expectedType)` exists and the body contains `expectedType.cast`.
- `GraphitronFacadeGeneratorTest.java:30-32` (the `containsExactly("buildSchema", "newExecutionInput", "newExecutionInput")` set): collapse to one `newExecutionInput` entry (or extend to N if the test SDL declares contextArguments, in which case the generator emits one factory whose param list reflects them).
- `GraphitronFacadeGeneratorTest.java:73-83` (`newExecutionInput_hasGraphitronContextAndDSLContextOverloads`): rewrite. Under R190 there is one schema-driven overload; assert the single overload's parameter list reflects the test SDL's contextArguments, alphabetical-sorted, with `DSLContext defaultDsl` first.
- `GraphitronFacadeGeneratorTest.java:85-92` (`newExecutionInput_bothOverloadsReturnExecutionInputBuilder`): rename to `newExecutionInput_returnsExecutionInputBuilder` and assert the one overload returns `ExecutionInput.Builder`.
- `TypeFetcherGeneratorTest.java:2044` and `:2210` (body assertions containing `graphitronContext(env).getTenantId(env)`): drop the `getTenantId(env)` clause from the asserted substring (the emitted name becomes path-only). Both sites share the same shape.
- `DataLoaderFetcherEmitterTest.java:57` (body assertion `name = graphitronContext(env).getTenantId(env) + "/" + java.lang.String.join("/", ...)`): drop the `getTenantId(env) + "/" +` segment from the asserted substring.

### Generator javadoc/comment cleanup

Comment-only sites in already-edited files that reference `getTenantId`. Fold into the same commits that touch the surrounding code:

- `TypeFetcherGenerator.java:4536-4541` — class-level javadoc on `buildDataLoaderName` describes the emitted name as `GraphitronContext.getTenantId(env) + "/" + path`. Rewrite to "path-only".
- `DataLoaderFetcherEmitter.java:22` — `<li>` bullet in the class-level javadoc says "resolve the path-scoped DataLoader name via `GraphitronContext.getTenantId` + ...". Drop the `getTenantId` mention; the name is path-scoped, full stop.
- `QueryNodeFetcherClassGenerator.java:145` — javadoc paragraph "keyed by `getTenantId(idEnv) + path`, where `idEnv` is a per-id DFE." Rewrite to "keyed by `path` (computed from the per-id DFE's execution-step-info)" so the per-id DFE construction stays motivated but the `getTenantId` reference disappears.

### Runtime contract (`graphitron-rewrite-runtime/`)

No new runtime surface for R190. The `getContextArgument` signature change is in the generated interface, not the runtime artifact. R192 introduces the runtime functional interface for the validator factory.

### User documentation

Twelve pages update in the same commit window as the generator change (plus one verified-no-change). The drafts and revision instructions live under "User documentation (first-client check)" below; the two primary rewrites (`graphitron-rewrite/docs/getting-started.adoc`, `graphitron-rewrite/docs/runtime-extension-points.adoc`) move their drafted prose into place, the other ten pages take the per-page revisions listed.

## Tests

- **Pipeline (L4).** `GraphitronFacadeGeneratorPipelineTest` (or the closest existing test for that generator) gains a case: SDL with multiple `@service(contextArguments: [...])` sites producing the alphabetical-sorted factory parameter list. Snapshot the emitted factory `TypeSpec` (including the `graphQLContext` builder lambda) and the sealed `GraphitronContext` interface + `GraphitronContextImpl` singleton. Loader-name snapshot for one of the five DataLoader sites pins the un-prefixed shape.
- **Classification (L2).** `ContextArgumentTypeAgreementTest` (new): two SDL fixtures, one accepted (single typeName per name across `@service` + `@tableMethod` + `@condition`), one rejected. The rejected fixture forces a three-site conflict (`@service` declares `(Long fnr)`, `@condition` declares `(Number fnr)`, `@tableMethod` declares `(String fnr)`) and asserts the rejection's typed structure: `name == "fnr"`, `sites.size() == 3`, each `ConflictSite` carrying the expected `MethodRef` and `TypeName declared`. A second accepted-but-assignability-shaped case (`(Number fnr)` and `(Long fnr)`) asserts that "assignable but not equal" is treated as a conflict, pinning the always-error semantics.
- **Validator (L4).** A new `ContextArgumentTypeAgreementValidationTest` lands under `graphitron/src/test/java/no/sikt/graphitron/rewrite/validation/` alongside the existing per-validator-case tests (`ServiceFieldValidationTest`, `TableMethodFieldValidationTest`, `ConnectionTypeValidationTest`). The case: the new `validateContextArgumentTypeAgreement` check, fed a `Rejection.contextArgumentTypeConflict` with three sites declaring three distinct types, produces one `ValidationError` whose rendered message names every site (each by its `MethodRef` coordinate, no truncation). The snapshot pins the renderer's behaviour against the typed `ConflictSite` list; a regression that drops or de-duplicates sites in the prose surface is caught here, separate from the L2 classifier assertion on the typed `sites` field.
- **Audit (L2).** `LoadBearingGuaranteeAuditTest` is already wired; the new key `context-argument.type-agreement` lands with one producer (the classifier) and two consumers (the factory emitter and the `ArgCallEmitter` call-site emitter) and the test stays green by construction. If the audit fails after the slice, the producer/consumer annotations are mis-keyed.
- **Compile (L5).** `graphitron-sakila-example` gains an SDL fragment with at least one `@service(contextArguments: ["userId"])` site so the new factory shape appears in the compile fixture; generated code passes `mvn compile -pl :graphitron-sakila-example -Plocal-db`.
- **Execute (L6).** `graphitron-sakila-example`'s test suite calls `Graphitron.newExecutionInput(dsl, userId)` and verifies the contextArgument round-trips through `getContextArgument` to the user method. A second test exercises the missing-value diagnostic by hand-rolling an `ExecutionInput.Builder` that omits the stash, and asserts the message text matches the documented form (substring match on `"call Graphitron.newExecutionInput(...)"`).

## User documentation (first-client check)

R190 changes the primary onboarding surface (`Graphitron.newExecutionInput`) and the runtime extension model (`GraphitronContext` becomes sealed, `getTenantId` disappears from the single-tenant shape). Per `docs/workflow.adoc` §"Plans with a user-visible surface", the docs draft is the first client of the design; if it doesn't read simply, the design is wrong. Tracing every `GraphitronContext` / `getTenantId` / `implements GraphitronContext` / `new GraphitronContext() {...}` reference through the manual and the architecture docs, R190 touches **fourteen pages required plus one optional touch-up and one verified-no-change**. The two primary onboarding rewrites carry drafted replacement prose below; the other twelve describe the actual current content of each page and the concrete shape of the revision. This is not a search-and-replace: each page's framing differs, several touch user-facing code blocks whose shape changes, and three pages get a deferral banner because their content is fully rescoped to R45's tenant-column work.

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

### Architecture orientation: `graphitron-rewrite/docs/README.adoc:7`

The first "You came here because…" bullet currently reads "You want to *extend the runtime*, implement `GraphitronContext`, route per-tenant `DSLContext`s, register custom scalars, hook in jOOQ listeners. → xref:runtime-extension-points.adoc[Runtime Extension Points]." Under R190, implementing the sealed `GraphitronContext` is no longer the extension model; per-tenant routing moves to R45. Rewrite to: "You want to *extend the runtime*, wire per-request values into `Graphitron.newExecutionInput(...)`, register custom scalars, hook in jOOQ listeners. → xref:runtime-extension-points.adoc[Runtime Extension Points]." (Per-tenant routing reads better re-added under R45 alongside the tenant-column work; until then, the link target's body already carries the deferral framing.)

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
- Per-tenant context test (lines 245-260): the block today overrides `getDslContext` (reading `env.getGraphQlContext().get("tenantId")` and indexing into a `tenantContexts` map) and `getContextArgument` (echoing the `tenantId` claim back). Replace it with a per-tenant `DSLContext` selection at request entry: the test computes the right `DSLContext` from the tenant claim before the request, then calls `Graphitron.newExecutionInput(perTenantDsl, ...)`. The multi-tenant assertion shape (the recipe's parallel to the `nodes_perTenantPartition_separateBatchPerTenant` execution-tier test in `graphitron-sakila-example`) is being rewritten under R45 alongside the tenant-column work; until then, this section either drops or carries the R45 deferral banner.

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
- `docs/manual/how-to/apollo-federation.adoc` lines 84-96 — delete the entire `== Per-tenant entity routing` subsection (heading at line 84 through the closing xref paragraph at line 96). The rest of the federation recipe (transport, key declaration, entity fetcher swapping) is unaffected by R190; the deleted subsection is reintroduced under R45 in a shape that matches the post-R190 sealed surface. Two constraint bullets later in the same file also reference per-rep `getTenantId` cooperation: line 116 ("Per-rep tenant routing reads off `env.getSource()`...") deletes outright; line 122 ("xref:tenant-scoping.adoc[How-to: Tenant scoping] covers per-rep tenant routing, the DataLoader cache-key contract, and the `getTenantId(repEnv)` shape...") rephrases to "xref:tenant-scoping.adoc[How-to: Tenant scoping] covers per-request `DSLContext` routing (recipe is being rewritten under R45)" so the cross-link still resolves but the prose stops naming a method the post-R190 interface no longer carries.
- `docs/manual/how-to/split-vs-inline.adoc` — three small edits on the same DataLoader-name discussion. (a) The inline code block at lines 44-45 currently renders the pre-R190 emitted form `String name = graphitronContext(env).getTenantId(env) + "/" + String.join("/", env.getExecutionStepInfo().getPath().getKeysOnly())`; rewrite to the post-R190 shape `String name = String.join("/", env.getExecutionStepInfo().getPath().getKeysOnly())` so the page's worked example stays a live mirror of the emitted code. (b) Delete the "Per-tenant key prefix" bullet at line 56 entirely — the registry is request-scoped, the cross-tenant collision scenario it warned about doesn't arise in practice (already labelled "rare but possible"), and under R190 the per-tenant prefix doesn't exist anyway. (c) The closing sentence at line 58 ("The combination 'path-scoped, request-scoped, tenant-prefixed' means inline-style mistakes...") drops the `tenant-prefixed` clause, becoming "The combination 'path-scoped, request-scoped' means inline-style mistakes...". R45 may reintroduce both the bullet and the prefix when `<tenantColumn>` is configured.

### Index-page updates (one line each)

- `docs/manual/reference/index.adoc:9` — the "Runtime API reference" bullet enumerates the interface methods as `(getDslContext, getContextArgument, getTenantId, getValidator)`. Drop `getTenantId` from the listing.
- `docs/manual/how-to/index.adoc:33` — "Tenant scoping: wire `getDslContext` and `getTenantId` on `GraphitronContext` so each request reaches a tenant-specific `DSLContext`." Rephrase to: "Tenant scoping: per-request routing through `Graphitron.newExecutionInput(dsl, ...)`. The full recipe (per-tenant DataLoader partitioning, `@tenantId` directive, `byTenant` factory overload) ships under R45; until then, single-tenant apps pass a single `DSLContext`."

### Small in-prose touch-ups (one or two sentences each)

- `docs/manual/explanation/how-it-works.adoc:32` — "*Calls into jOOQ.* The fetcher reads the per-request `DSLContext` off the `GraphitronContext`, builds a typed query..." → "*Calls into jOOQ.* The fetcher reads the per-request `DSLContext` off the `GraphQLContext` that `Graphitron.newExecutionInput(dsl, ...)` populated, builds a typed query..." The sentence's structure stays; only the source-of-truth name changes.
- `docs/manual/explanation/batching-model.adoc:45` — "...the xref:../reference/runtime-api.adoc[runtime API] documents the per-request `GraphitronContext` interface; the host application's job is just to provide the `DSLContext` and any context arguments." → "...the xref:../reference/runtime-api.adoc[runtime API] documents the sealed `GraphitronContext` contract; the host application's job is to call `Graphitron.newExecutionInput(dsl, ...)` with the `DSLContext` and any contextArgument values, and the generator wires the per-request `GraphQLContext` from those typed parameters." The tenant-scoping xref later in the paragraph stays (now points at the banner-bearing recipe).
- `docs/manual/tutorial/06-going-further.adoc:23` — "...walks through copying that pattern into your own project: how to wire `GraphitronContext` against Testcontainers..." → "...walks through copying that pattern into your own project: how to wire `Graphitron.newExecutionInput(dsl, ...)` against Testcontainers..."
- `docs/security.adoc:21` — "See xref:architecture/runtime-extension-points.adoc[Runtime Extension Points] for how that wires together with `GraphitronContext`." → "See xref:architecture/runtime-extension-points.adoc[Runtime Extension Points] for how that wires through the per-request `DSLContext` you pass to `Graphitron.newExecutionInput(dsl, ...)`." Optional; the existing phrasing is technically still accurate (sealed `GraphitronContext` is the per-request contract), but the rewrite reads better post-R190.
- `docs/manual/how-to/add-custom-conditions.adoc:166` — the bullet currently reads "A typo in `contextArguments` surfaces as a `null` parameter at request time, not as a build error." R190's typed factory parameter list plus the per-slot `Objects.requireNonNull` change this surface: a typo in the SDL `contextArguments:` list now produces a typed-but-mis-named factory parameter the consumer must populate at compile time, and passing `null` to fill that slot is a tight NPE at the factory call rather than a silent `null` reaching the condition method. Rephrase to: "A typo in `contextArguments` surfaces at compile time as a typed-but-unfamiliar parameter on `Graphitron.newExecutionInput(...)`'s signature: the factory reflects the SDL's `contextArguments:` list verbatim, so a misspelled name produces a factory parameter under the misspelled name. The error you see is the consumer-side compile failure (or, if you populate the slot with `null`, an `NullPointerException` at the factory call), not a silent `null` at request time."

### Verified-no-change (cross-links remain coherent)

`docs/manual/how-to/condition-cascade.adoc:181` cross-links to `tenant-scoping.adoc` and `runtime-api.adoc` for the supplying-side context-argument story. The cross-link remains coherent once those two targets land; no edits needed on the page itself. (`add-custom-conditions.adoc:164` was previously listed here; line 166 of the same page now requires a touch-up — see "Small in-prose touch-ups" above. The line 164 cross-link itself stays as-is.)

### Total scope (fourteen pages plus one optional and one no-change)

Substantive rewrites (four): `getting-started.adoc`, `runtime-extension-points.adoc`, `runtime-api.adoc`, `test-your-schema.adoc`.
Tenant-routing deferral banners (three): `tenant-scoping.adoc`, `apollo-federation.adoc` (one subsection plus two constraint bullets), `split-vs-inline.adoc` (one code snippet, one bullet, one closing-sentence clause).
Architecture orientation (one): `graphitron-rewrite/docs/README.adoc` (one bullet at line 7).
Index-page updates (two): `reference/index.adoc`, `how-to/index.adoc`.
Small in-prose touch-ups (four): `how-it-works.adoc`, `batching-model.adoc`, `06-going-further.adoc`, `add-custom-conditions.adoc`.
Optional touch-up (one): `security.adoc` (cross-link reads coherently as-is; rewrite is a polish).
Verified-no-change (one): `condition-cascade.adoc`.

The implementer is on hook to land all fourteen required edits in the same commit window as the generator change, so the docs site does not ship in a half-state. If a fifteenth page surfaces during implementation, fold it into the same commit window with the same "first-client check" lens: would a consumer arriving at this page from search read code that compiles against the post-R190 generated `GraphitronContext`?

## Open questions

None outstanding for the single-tenant slice.

## Roadmap entries (siblings / dependencies)

- **Splits from** [`tenant-routing-and-execution-input.md`](tenant-routing-and-execution-input.md) (R45). R45 awaits this landing before its Spec rescopes to the multi-tenant additions on top.
- **Reshapes** [`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md) (R46) transitively via R45: the public `ContextValueRegistration` permit and `GraphitronContext` extension-point assumptions R46 was built on dissolve here.
- **Affects** [`helper-emission-non-fetcher-hosts.md`](helper-emission-non-fetcher-hosts.md) (R85). The host-class `graphitronContext(env)` helper-emission gate stays structurally unchanged because the sealed interface keeps the same method set in single-tenant mode (no `getTenantId` to call). If R45 widens that set, R85 sees the addition cleanly.
- **Coordinates with** [`dslcontext-on-condition-tablemethod.md`](dslcontext-on-condition-tablemethod.md): both touch `ArgCallEmitter`'s param walk; no shared file edits but adjacent emission paths.
- **Spawns** [`custom-validator-factory.md`](custom-validator-factory.md) (R192) as the carved-out validator-override item.
