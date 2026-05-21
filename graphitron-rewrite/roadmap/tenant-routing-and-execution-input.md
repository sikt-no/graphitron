---
id: R45
title: "Multi-tenant routing on top of the schema-driven ExecutionInput factory"
status: Spec
bucket: architecture
priority: 5
theme: service
depends-on: []
last-updated: 2026-05-20
---

# Multi-tenant routing on top of the schema-driven ExecutionInput factory

## Motivation

R190 landed the single-tenant slice — a schema-driven `Graphitron.newExecutionInput(DSLContext defaultDsl, …contextArguments)` factory, a sealed `GraphitronContext` consumers cannot implement, typed `getContextArgument(env, name, ExpectedType.class)` diagnostics, and cross-site contextArgument type-agreement enforced at validate time. R190 deliberately removed `getTenantId(env)` from the interface, dropped the `getTenantId(env) + "/"` prefix from all five DataLoader-name emission sites, and collapsed the federation `_entities` per-tenant grouping at `HandleMethodBody.java`. R45 layers multi-tenant routing back on top of that landed baseline.

Three additions, all gated on a new `<tenantColumn>` Mojo element:

1. **Tenant-column classification + `byTenant` factory overload.** When the consumer configures `<tenantColumn>`, codegen classifies every catalog table as tenant-scoped (carries the named column) or global (does not). A second `newExecutionInput` overload appears alongside R190's single-tenant baseline, taking a `Function<T, DSLContext> byTenant` slot so per-request DSLContext routing can dispatch on a tenant id. Absent the Mojo element, the single-tenant shape stays as-shipped.

2. **Conditional DataLoader name partitioning.** The five DataLoader emission sites R190 stripped to path-only names re-add the `getTenantId(env) + "/"` prefix selectively: tenant-scoped target types get the prefix back so cross-tenant cache collisions can't occur; global types stay path-only so loaders for reference data are shared. The federation `_entities` per-tenant grouping at `HandleMethodBody.java` re-introduces the inner `Map<String, …>` level when `<tenantColumn>` is configured, restoring the per-tenant batching shape.

3. **`@tenantId` ARGUMENT_DEFINITION directive.** A new directive marks the GraphQL argument that drives DSLContext routing for a field; codegen reflects the marked argument's Java type, validates assignability against the configured tenant `T`, and emits `byTenant.apply(...)` at the dispatch site. Schemas combining a `@tenantId` argument with `tenantId` in the same field's `contextArguments` list reject — two competing sources of the same value.

The interface widening (`getTenantId(env)` returns `T` rather than `String`, plus a new `getDslContext(T tenantId)` for the multi-tenant dispatch) is additive on the sealed `GraphitronContext` R190 shipped; it is not a breaking signature change for code that compiled against R190's three-method shape.

## Design

### Tenant column declaration

A single Mojo element declares the app's tenant column, by name and Java type:

```xml
<tenantColumn>
  <name>tenant_id</name>
  <javaType>java.lang.Long</javaType>
</tenantColumn>
```

One column per app. `<javaType>` drives the Java type `T` everywhere graphitron talks about tenant ids — the `byTenant` factory parameter, the configured-into-`GraphitronContext` return type, the `@tenantId` arg-type check.

When `<tenantColumn>` is absent, graphitron runs in single-tenant mode: no multi-tenant factory overload, no DataLoader name partitioning by tenant, no `byTenant` shape anywhere in the emitted surface.

### Tenant-scope classification

At codegen, after the jOOQ catalog is loaded, every table is classified:

- **Tenant-scoped** — contains a column whose name matches `<tenantColumn><name>`.
- **Global** — does not.

The classification is computed once during catalog loading and consulted at three places: the multi-tenant factory generation (does any reachable graph type sit on a tenant-scoped table?), the DataLoader emission sites (which name prefix do we emit?), and the `@tenantId` arg-type check (the marked arg must be assignable to the configured `T`).

### Multi-tenant factory overload

Post-R190, `GraphitronFacadeGenerator` emits a single schema-driven `newExecutionInput(DSLContext defaultDsl, …contextArguments)` factory whose parameter list reflects the SDL's contextArguments alphabetically, with reflected Java types pinned by the cross-site type-agreement classifier. R45 adds a second overload that appears IFF `<tenantColumn>` is configured.

For a schema using `@service(contextArguments: ["userInfo", "fnr"])` with no tenant column configured (the R190 baseline, unchanged):

```java
public static ExecutionInput.Builder newExecutionInput(
    DSLContext defaultDsl,
    String fnr,
    UserInfo userInfo);
```

For the same schema with `<tenantColumn>` configured:

```java
public static ExecutionInput.Builder newExecutionInput(
    DSLContext defaultDsl,
    String fnr,
    UserInfo userInfo);                                  // single-tenant fallback (unchanged from R190)

public static ExecutionInput.Builder newExecutionInput(
    DSLContext defaultDsl,
    Function<Long, DSLContext> byTenant,
    String fnr,
    UserInfo userInfo);                                  // multi-tenant
```

The `byTenant` slot sits second, immediately after `defaultDsl` and before the alphabetical-sorted contextArguments, so the parameter ordering stays predictable across consumers. The multi-tenant overload populates `GraphQLContext` with `byTenant` under the typed key `byTenant.class` (or a generated marker class — pick during implementation) so the singleton impl's `getDslContext(T tenantId)` method can read it back.

The `byTenant` factory and the per-tenant DSLContext routing are *independent of* whether `tenantId` appears in any `contextArguments` list. They are driven by the Mojo config alone; the schema's per-field tenant id source is the value fed into `byTenant.apply(...)` at the dispatch site (see "Sources for the per-field tenant id" below).

Both overloads return graphql-java's `ExecutionInput.Builder` unchanged. Internally each factory routes through the same `Objects.requireNonNull` + `graphQLContext.put` shape R190 emits; R45 adds the `byTenant` null-check to the multi-tenant body and stashes the function reference into the per-request context.

### Sources for the per-field tenant id

A given field may source its tenant id from either:

- **Request scope, via contextArguments.** If `@service(contextArguments: ["tenantId", ...])` lists `tenantId`, the factory grows a `Long tenantId` parameter alongside the others. The consumer extracts it in their HTTP filter (typically from JWT / session) and passes it in.
- **GraphQL argument, via `@tenantId`.** A new ARGUMENT_DEFINITION directive marks the argument that drives DSLContext routing for the field:

  ```graphql
  findCustomerOrders(
    customerCompanyId: ID! @tenantId,
    status: OrderStatus
  ): [Order!] @table(name: "order")
  ```

  At runtime, the field extracts the marked argument's value and calls `byTenant.apply(value)`. The marked argument's GraphQL type must produce a value assignable to the configured tenant `T`; the codegen rejects mismatches at validation time.

A field carrying both a `@tenantId` argument and `tenantId` in its `contextArguments` is rejected at codegen — two competing sources of the same value. At most one `@tenantId` argument per field; the directive applies to ARGUMENT_DEFINITION only.

### `GraphitronContext` method-set widening

R190 shipped the sealed `GraphitronContext` with three default methods (`getDslContext`, `getContextArgument`, `getValidator`) and a generated `GraphitronContextImpl` singleton. R45 widens the method set additively on the same sealed surface:

- `T getTenantId(DataFetchingEnvironment env)` — returns the configured tenant `T` from `env.getGraphQlContext()` under the typed key the multi-tenant factory populates. Default body: `env.getGraphQlContext().get(TenantId.class)` (or whatever marker the factory stashes under). Returns `null` when the consumer called the single-tenant factory overload (no tenant id supplied); the five DataLoader emission sites null-check the result before composing the prefix.
- `DSLContext getDslContext(T tenantId)` — calls back into the `byTenant` function the multi-tenant factory stashed on `GraphQLContext`. Used by the five DataLoader emission sites and the `@tenantId`-arg dispatch path so they don't re-read `byTenant` from `GraphQLContext` on every call.

Both methods are `default` on the sealed interface; the singleton impl needs no body since the default reads suffice. The interface stays sealed + permits the singleton; consumers still cannot implement it from outside. R190's three existing methods are unchanged.

R85's planned host-class helper emission keeps working: `graphitronContextCall()` is the same call shape; only the method set widens.

### DataLoader name partitioning (re-add prefix conditionally)

Post-R190 every DataLoader name is `path` alone at five emission sites (`DataLoaderFetcherEmitter.java:135`, `TypeFetcherGenerator.java:4553`, `MultiTablePolymorphicEmitter.java:810` and `:876`, `QueryNodeFetcherClassGenerator.java:161`). R45 re-introduces the tenant prefix conditionally:

- **Tenant-scoped target type with `<tenantColumn>` configured:** name is `String.valueOf(getTenantId(env)) + "/" + path` — the `getTenantId(env)` call returns the configured `T`, stringified for the loader-name key. Cross-tenant cache collisions can't occur because each tenant's data lives in a distinct loader.
- **Global target type:** name stays `path` alone, regardless of whether `<tenantColumn>` is configured. Loaders for reference data shared across tenants stay shared.
- **`<tenantColumn>` unconfigured:** every site stays at `path`-only, the R190 default; classification treats every type as global by construction.

The five emission sites add a classifier-driven branch: the catalog-loading tenant-scope classification (see "Tenant-scope classification" above) is consulted at emit time to pick prefix vs no-prefix per site. The `getTenantId(env)` call only appears in the prefix branch; sites emitting global names don't reference the method at all.

### Federation entity dispatch grouping (re-introduce tenant level)

R190 collapsed the federation `_entities` dispatch grouping at `HandleMethodBody.java` from `Map<Integer, Map<String, List<Object[]>>>` to `Map<Integer, List<Object[]>>` when it removed `getTenantId`. R45 re-introduces the inner-tenant level when `<tenantColumn>` is configured:

- The per-rep DFE construction stays as-shipped (R190 left `repEnv = newDataFetchingEnvironment(env).arguments(rep).build()` in place so any in-rep argument reads still work).
- A new `String tenantId = String.valueOf(graphitronContext(repEnv).getTenantId(repEnv))` line re-appears at `HandleMethodBody.java`'s `emitDecodeAndGroup` body.
- The `groups` declaration widens back to `Map<Integer, Map<String, List<Object[]>>>`.
- The per-rep grouping call becomes `groups.computeIfAbsent(altIndex, k -> new LinkedHashMap<>()).computeIfAbsent(tenantId, k -> new ArrayList<>()).add(...)`.
- `emitGroupDispatch` re-adds the inner-entry iteration loop and reads bindings off the inner entry.
- The class-level javadoc at `HandleMethodBody.java` and the sister doc comments at `EntityFetcherDispatchClassGenerator.java` / `QueryNodeFetcherClassGenerator.java` re-add the `getTenantId(repEnv)` mention in the per-rep DFE rationale.

When `<tenantColumn>` is unconfigured, the federation grouping stays at R190's collapsed shape (single-level `Map<Integer, List<Object[]>>`); the tenant level only re-appears when the Mojo element drives the emit branch.

### `@tenantId` arg validation diagnostics

Rejections specific to the multi-tenant additions, all surfaced through `Rejection` arms and routed through `GraphitronSchemaValidator.validate` per R190's validator-mirrors-classifier pattern:

- `Rejection.tenantIdArgTypeMismatch(ConflictSite site, TypeName declared, TypeName expected)` — the `@tenantId`-marked argument's GraphQL-resolved Java type is not assignable to the configured `<tenantColumn><javaType>`.
- `Rejection.duplicateTenantIdSource(ConflictSite site, String fieldName)` — the same field carries both a `@tenantId` argument and lists `tenantId` in its `contextArguments`.
- `Rejection.multipleTenantIdArgs(ConflictSite site, String fieldName, List<String> argNames)` — more than one argument on the field is marked `@tenantId`.

All three follow the typed-record-plus-`message()`-renderer pattern R190 pinned for `AuthorError.TypeConflict`.

## Implementation

### Mojo configuration (`graphitron-maven/`)

- New `<tenantColumn>` element with `<name>` (column name) and `<javaType>` (FQN). Both required when the element is present; absence drives the single-tenant fallback path (R190's shape, unchanged).

### Catalog / classification (`graphitron/`)

- Tenant-scope classification during catalog loading: produces a `Set<TableName>` of tenant-scoped tables, plus the configured `T` as a `TypeName`. Stored on the build result for downstream consumers (the five DataLoader emission sites, the federation grouping site, and the `@tenantId`-arg dispatcher).
- New rejections (typed records with `message()` overrides per R190's `AuthorError.TypeConflict` precedent): `Rejection.tenantIdArgTypeMismatch`, `Rejection.duplicateTenantIdSource`, `Rejection.multipleTenantIdArgs`.
- Validator-mirror surface: `GraphitronSchemaValidator.validate` gains `validateTenantIdArgs(schema, errors)`, draining the new rejection list into `ValidationError`s the same way R190's `validateContextArgumentTypeAgreement` does.

### Facade generation (`generators/schema/GraphitronFacadeGenerator.java`)

- The single-tenant overload R190 emits stays unchanged.
- When `<tenantColumn>` is configured, emit a second `newExecutionInput` overload with `Function<T, DSLContext> byTenant` slotted second (between `DSLContext defaultDsl` and the alphabetical contextArguments). Body: `Objects.requireNonNull(byTenant, "byTenant")` after the `defaultDsl` null-check, then stash `byTenant` on `graphQLContext` under a typed marker key.

### `GraphitronContext` (`generators/util/GraphitronContextInterfaceGenerator.java`)

- Widen the sealed interface's method set additively: add `default T getTenantId(DataFetchingEnvironment env)` (reads the typed marker from `env.getGraphQlContext()`, returns `null` when the marker is absent) and `default DSLContext getDslContext(T tenantId)` (reads the stashed `byTenant` and invokes it).
- Configured `T` is the JavaPoet `TypeName` from `<tenantColumn><javaType>`; both new methods are emitted parameterised by it.
- R190's three existing methods (`getDslContext(env)`, `getContextArgument(env, name, expectedType)`, `getValidator(env)`) stay unchanged.

### Emission sites

- **Five DataLoader name sites:** branch on the target type's tenant-scope classification at emit time. Tenant-scoped target with `<tenantColumn>` configured emits `String.valueOf(graphitronContext(env).getTenantId(env)) + "/" + path`; everything else stays path-only (the R190 default).
- **Federation entity dispatch grouping (`HandleMethodBody.java`):** re-add the `String tenantId = ...` line, widen the `groups` declaration back to the nested-map shape, restore the inner-entry iteration in `emitGroupDispatch`, and revise the class-level javadoc to mention `getTenantId(repEnv)` again. Only emitted when `<tenantColumn>` is configured; the un-tenanted path stays at R190's collapsed shape.
- **`@tenantId`-arg dispatch (`ArgCallEmitter` / the field-fetcher emitter that wires `byTenant`):** at the marked argument's slot, emit `graphitronContext(env).getDslContext(env.getArgument("customerCompanyId"))` so the field's DSLContext is the per-tenant one for the duration of the fetch.

### Schema (`schema/directives.graphqls`)

- New `directive @tenantId on ARGUMENT_DEFINITION`. The shipping `directives.graphqls` already auto-injects per R190; the new directive lands on the same file.

### Re-enabling commented-out tests

R190 commented out two execution-tier tests in `graphitron-sakila-example` (`querydb/FederationEntitiesDispatchTest.java`'s `entities_multiTenancyPartition_oneSelectPerTenant` and `querydb/GraphQLQueryTest.java`'s `nodes_perTenantPartition_separateBatchPerTenant`) with a forward-reference comment naming this item. R45 uncomments both methods in the same commit window as the generator changes and reshapes the test fixtures against the new sealed surface: per-tenant `DSLContext` selection happens at request entry now (`Graphitron.newExecutionInput(defaultDsl, byTenant, ...)`), not through a consumer-supplied `GraphitronContext` impl. Assertion shapes (`QUERY_COUNT == 2` for the partitioning case, the per-rep dispatch invariant for the federation case) stay as the canonical execution-tier proofs.

## Tests

- **Catalog (L1/L2).** Tenant-column classification: tables with the configured column tag tenant-scoped, others global. Mojo without `<tenantColumn>` ⇒ all tables global, no multi-tenant overload emitted.
- **Classification (L2).** New `TenantIdArgClassificationTest`: SDL fixtures exercise the three new rejections (`tenantIdArgTypeMismatch` with a mismatching arg type, `duplicateTenantIdSource` with `@tenantId` + `tenantId` contextArgument on the same field, `multipleTenantIdArgs` with two `@tenantId`-marked args). Each accept-case fixture asserts the typed `Rejection` carrier; the rejected fixture asserts both the typed record and the rendered `message()` shape.
- **Validation (L4).** New `TenantIdArgValidationTest` under `graphitron/src/test/java/no/sikt/graphitron/rewrite/validation/`, mirroring R190's `ContextArgumentTypeAgreementValidationTest`: feeds each new `Rejection` into `validateTenantIdArgs` and asserts the rendered `ValidationError` shape.
- **Pipeline (L4).** Extend the R190 `GraphitronFacadeGenerator` pipeline test (the case that snapshots the single-tenant factory) with a second case: same SDL plus `<tenantColumn>` configured. Snapshot asserts both overloads emit, the `byTenant` parameter sits second on the multi-tenant overload, and the singleton impl's two new methods (`getTenantId(env)`, `getDslContext(T)`) appear. Loader-name partitioning snapshot covers both branches (tenant-scoped target ⇒ prefixed, global target ⇒ path-only). Federation grouping snapshot covers the nested-map re-introduction at `HandleMethodBody`.
- **Audit (L2).** New `@LoadBearingClassifierCheck` keys for the multi-tenant additions (one per new classifier check) — the producer/consumer wiring is verified by `LoadBearingGuaranteeAuditTest`'s existing scan; no new audit-test code, the audit stays green by construction.
- **Compile (L5).** `graphitron-sakila-example` gains a multi-tenant schema fixture using both factory overloads, the `@tenantId` arg directive, and at least one global-scope reference-data fetch. Generated code passes `mvn compile -pl :graphitron-sakila-example -Plocal-db`.
- **Execute (L6).** R190's existing two commented-out tests (`entities_multiTenancyPartition_oneSelectPerTenant`, `nodes_perTenantPartition_separateBatchPerTenant`) uncomment and reshape against the new sealed surface. New execution-tier coverage: single-request multi-tenant routing through `byTenant`, loader-name partitioning verified by query count + the `DataLoader.getCacheKey` introspection, `@tenantId` arg routing verified by per-tenant table isolation.

## Open questions

1. **`@tenantId` syntactic form.** Currently drafted as a per-argument directive. Alternatives considered: sigil in argMapping value (`argMapping: "customerCompanyId: $tenantId"`); top-level directive arg (`@service(..., tenantIdArg: "customerCompanyId")`). Per-arg directive picked because the marker is a property of the argument, local to its definition, and the existing `argMapping` slot's "GraphQL arg → Java param name" semantics stays single-purpose. Revisit if R46's fan-out cousin (analogous "this arg drives fan-out") finds the sigil shape natural and the per-arg-directive vs sigil divergence becomes friction.
2. **Multi-tenant overload when the reachable graph has no tenant-scoped types.** Currently emitted whenever `<tenantColumn>` is configured. Alternative: skip emission if classification finds no tenant-scoped reachable types. The conservative emission is harmless; revisit only if it causes IDE noise.
3. **Marker key for `byTenant` on `GraphQLContext`.** The multi-tenant factory stashes the `Function<T, DSLContext>` under a typed key so the singleton impl's `getDslContext(T)` can read it back. Options: a generated marker class adjacent to `GraphitronContextImpl`, or a structural `Class<? extends Function>` literal. Pick during implementation; the consumer-facing API is unchanged either way. *Dissolves under Q4's alternative direction (no `byTenant` stash, no `getDslContext(T)` method).*
4. **`getTenantId`/`getDslContext` on the sealed interface vs per-fetcher emission against a classified `TenantIdSource`.** *Likely revision target before the next Spec → Ready pass.* The current Design adds two methods to the sealed `GraphitronContext` (`T getTenantId(env)` and `DSLContext getDslContext(T tenantId)`) and stashes `byTenant` on `GraphQLContext` under a typed marker key. An architectural-principles pass on the current draft (2026-05-20) surfaced three composing findings:

   - The new `getDslContext(T)` is structurally a thunk over `byTenant`, not a context method (the singleton impl just reads `byTenant` off `GraphQLContext` and invokes it; the method's signature carries no per-request information the call site doesn't already have).
   - The new `getTenantId(env)` returning `null` on the single-tenant factory path is the trans-axis return shape `SourceKey` exists to prevent — one method, two return arms gated on Mojo config the consumer can't see from the type.
   - Stashing `byTenant` on `GraphQLContext` under a typed marker key is the wire-format-leak family the principles call out (per-request *routing function*, not a per-request *value* the fetcher walks).

   All three compose to a single alternative: drop both new interface methods, emit per-fetcher reads against the classified tenant-id source. The natural classifier-side shape is a new sealed sub-taxonomy `TenantIdSource` carrying the per-field decision — arms like `ArgSource(argName)` for `@tenantId`-marked args, `SourceColumn(colRef)` for a parent row whose backing class carries the tenant column, `ContextArgSource(name)` for the contextArguments path, and `RequestRootArgSource(argName)` for propagation from the operation root through the query tree. Each fetcher reads off its classified arm; the five DataLoader name-emission sites and `HandleMethodBody.java`'s federation grouping consume the same axis.

   If this direction wins, the sealed `GraphitronContext` keeps R190's three-method shape unchanged, "additive widening" stops being load-bearing on the R190 → R45 carve, and Q3 dissolves entirely. R190 doesn't change either way — its removal of `getTenantId(env)` is exactly the ground this direction stands on.

   Cost: a new sub-taxonomy on the field model plus a new per-field classifier step (with the validator-mirror surface that goes with any classifier-side decision). Reshapes §"GraphitronContext method-set widening" (deletes), §"DataLoader name partitioning" (reframes around `TenantIdSource`), §"Federation entity dispatch grouping" (reads tenantId from the rep, not from a method), and §"Sources for the per-field tenant id" (classifies into the new sub-taxonomy explicitly).

## Roadmap entries (siblings / dependencies)

- **Depends on** [`single-tenant-execution-input-factory.md`](single-tenant-execution-input-factory.md) (R190). The single-tenant factory, sealed `GraphitronContext`, typed `getContextArgument`, cross-site type-agreement check, `Rejection.contextArgumentTypeConflict`, the validator-mirrors-classifier wiring, the consumer-migration baseline in `graphitron-sakila-example`, and the user-doc revision pass all ship under R190. R45 is the strictly-additive multi-tenant slice on top of that baseline.
- **Reshapes** [`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md) (R46). R46's prior design built on a publicly-implemented `ContextValueRegistration<FanOut>` permit — which R190 already dissolved when it sealed the interface. R46 will be redesigned: the fan-out source becomes a list-typed `contextArgument` (e.g. `["fnr", "tenantIds"]` with `List<Long> tenantIds` on the factory R190 lands) plus a new directive arg (`fanOutOver:`) or per-arg directive (`@fanOut`). R45 leaves the hooks for R46: list-typed factory parameters carry the reflected parameterised type unchanged, and emission makes no scalar-only assumption about contextArguments.
- **Affects** [`helper-emission-non-fetcher-hosts.md`](helper-emission-non-fetcher-hosts.md) (R85). The host-class `graphitronContext(env)` helper-emission gate widens cleanly to the new method set: `getTenantId(env)` and `getDslContext(T)` ride the same shim R85 already plans for.
- **Coordinates with** [`dslcontext-on-condition-tablemethod.md`](dslcontext-on-condition-tablemethod.md): both touch `ArgCallEmitter`'s param walk. No shared file edits but adjacent emission paths.
- **Independent of** [`service-short-classname-resolution.md`](service-short-classname-resolution.md).
