---
id: R45
title: "Multi-tenant routing on top of the schema-driven ExecutionInput factory"
status: Spec
bucket: architecture
priority: 5
theme: service
depends-on: [connection-transaction-lifecycle]
last-updated: 2026-07-03
---

# Multi-tenant routing on top of the schema-driven ExecutionInput factory

## Reopened to Spec (2026-07-03): reconcile the routing seam with R429

This item was `Ready`, but R429 (`connection-transaction-lifecycle`) changes the substrate this design
routes over, so the reviewer-approved shape needs rework before implementation. R429 has graphitron take a
**`DataSource`** (owning connection acquisition and transaction demarcation) rather than a consumer-built
`DSLContext`, and it resolves multi-tenancy two ways: a shared database where the **tenant is an RLS session
value**, and database-per-tenant via a **`Map<TenantId, DataSource>`**. That **supersedes this item's
`byTenant Function<T, DSLContext>` overload**: graphitron now builds the `DSLContext` from the routed
`DataSource`, so a function returning a `DSLContext` is the wrong seam. What survives and is still this
item's to own is the *schema-level* routing model, unchanged in spirit: the `<tenantColumn>` classification,
the per-field `TenantIdSource` axis, the `@tenantId` argument directive, and the DataLoader-name / federation
`_entities` partitioning. The rework is to re-target the *how does a request pick its database/identity* half
from `byTenant`-returns-`DSLContext` onto R429's `Map<TenantId, DataSource>` lookup (db-per-tenant) and RLS
session value (shared DB), keyed off the same contextArgument tenant selector. Depends on R429; do not
implement the `byTenant` mechanism as written. The `byTenant` design below is retained for lineage until the
Spec revision replaces it.

## Motivation

R190 landed the single-tenant slice — a schema-driven `Graphitron.newExecutionInput(DSLContext defaultDsl, …contextArguments)` factory, a sealed `GraphitronContext` consumers cannot implement (three default methods: `getDslContext(env)`, `getContextArgument(env, name)`, `getValidator(env)`, with the Java cast emitted at each `getContextArgument` call site rather than threaded through a `Class<T>` parameter), and cross-site contextArgument type-agreement enforced at validate time. R190 deliberately removed `getTenantId(env)` from the interface, dropped the `getTenantId(env) + "/"` prefix from all five DataLoader-name emission sites, and collapsed the federation `_entities` per-tenant grouping in `HandleMethodBody`. R45 layers multi-tenant routing back on top of that landed baseline.

**This Spec was reworked 2026-06-26 to resolve the prior draft's Open Question Q4 and reconcile with R316.** The 2026-05-20 draft layered tenant routing by *widening* the sealed `GraphitronContext` with two new methods (`getTenantId(env)` / `getDslContext(T)`) and stashing the routing function under a marker key. Q4 already flagged that as a likely revision target on three principle-grounds. In the interval R316 landed the `(source, operation, target)` field-model pivot (`OutputField` answers `source()` / `operation()` / `target()`, each a sealed hierarchy built by the leaf producers). The revised design takes Q4's alternative: **leave `GraphitronContext` at R190's three-method shape unchanged**, and classify a per-field sealed `TenantIdSource` overlay that each fetcher reads, sitting alongside R316's three axes. The sections below are written against that resolved direction; the dropped interface-widening design is recorded for lineage in [Open questions](#open-questions).

Three additions, all gated on a new `<tenantColumn>` Mojo element:

1. **Tenant-column classification + `byTenant` factory overload.** When the consumer configures `<tenantColumn>`, codegen classifies every catalog table as tenant-scoped (carries the named column) or global (does not). A second `newExecutionInput` overload appears alongside R190's single-tenant baseline, taking a `Function<T, DSLContext> byTenant` slot so per-request DSLContext routing can dispatch on a tenant id. The `byTenant` function travels on `GraphQLContext` under a generated marker key, exactly as R190 already stashes the per-request `defaultDsl` under `DSLContext.class`. Absent the Mojo element, the single-tenant shape stays as-shipped.

2. **`TenantIdSource`-driven DataLoader name partitioning and federation grouping.** A per-field classified `TenantIdSource` (see [Design](#tenantidsource-the-per-field-routing-axis)) decides where each field's tenant key comes from. The five DataLoader emission sites R190 stripped to path-only names re-add a `tenantKey + "/"` prefix when the field's source is a request-scoped tenant key and its target type is tenant-scoped; global types stay path-only so loaders for reference data are shared. The federation `_entities` grouping in `HandleMethodBody` re-introduces the inner per-tenant `Map<String, …>` level when `<tenantColumn>` is configured. Both consumers read the classified axis, not a `getTenantId(env)` method.

3. **`@tenantId` ARGUMENT_DEFINITION directive.** A new directive marks the GraphQL argument that drives DSLContext routing for a field; codegen classifies the marked argument into the `TenantIdSource.ArgSource` arm, validates its reflected Java type is assignable to the configured tenant `T`, and emits the `byTenant.apply(...)` dispatch at the field's SQL entry. Schemas combining a `@tenantId` argument with `tenantId` in the same field's `contextArguments` list reject — two competing sources of the same value.

`GraphitronContext` does **not** widen: the routing function rides `GraphQLContext` (point 1) and the per-field tenant key is the classified `TenantIdSource` (point 2), so no `getTenantId(env)` / `getDslContext(T)` methods are added. R190's three-method sealed shape is the stable baseline.

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

The classification is computed once during catalog loading and consulted at three places: the multi-tenant factory generation (does any reachable graph type sit on a tenant-scoped table?), the per-field `TenantIdSource` classifier (does this field's target type sit on a tenant-scoped table, and can a child field read the tenant column off its parent row?), and the `@tenantId` arg-type check (the marked arg must be assignable to the configured `T`).

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

The `byTenant` slot sits second, immediately after `defaultDsl` and before the alphabetical-sorted contextArguments, so the parameter ordering stays predictable across consumers. The multi-tenant overload populates `GraphQLContext` with `byTenant` under a generated marker key (see [Open questions](#open-questions) Q3), exactly as R190 already populates `GraphQLContext` with the per-request `defaultDsl` under `DSLContext.class`. The dispatch site reads `byTenant` back off `GraphQLContext` inline at the field's SQL entry; no new `GraphitronContext` method intermediates the read.

The `byTenant` factory and the per-tenant DSLContext routing are *independent of* whether `tenantId` appears in any `contextArguments` list. They are driven by the Mojo config alone; the schema's per-field tenant id source is the value fed into `byTenant.apply(...)` at the dispatch site (see [`TenantIdSource`](#tenantidsource-the-per-field-routing-axis) below).

Both overloads return graphql-java's `ExecutionInput.Builder` unchanged. Internally each factory routes through the same `Objects.requireNonNull` + `graphQLContext.put` shape R190 emits; R45 adds the `byTenant` null-check to the multi-tenant body and puts the function reference into the per-request context.

### `TenantIdSource`: the per-field routing axis

The 2026-05-20 draft put the tenant lookup on the sealed `GraphitronContext` (`getTenantId(env)` returning `null` on the single-tenant path, `getDslContext(T)` thunking over `byTenant`). The reworked design replaces both with a per-field classified axis, **sibling to R316's `source()` / `operation()` / `target()`** but an *optional overlay*, not a fourth mandatory dimension: tenancy is a deployment-config concern orthogonal to what a field does, so forcing every field in every single-tenant build to carry a tenant dimension is the cross-product disease R316 fought. The axis is computed only when `<tenantColumn>` is configured; **in single-tenant builds the axis is absent, not "every field `Untenanted`"** (no per-field pass runs at all).

`TenantIdSource` splits along two facts that the prior single enum conflated — *scope* (is the tenant key uniform across a DataLoader batch, or per-source-row?) and *binding* (which argument / contextArgument / column carries it?). Scope is made structural so each consumer accepts only the arms it can legally handle, rather than `instanceof`-rejecting the rest at emit time:

```java
sealed interface TenantIdSource {

    /** Field touches no tenant-scoped table (global reference data). Routes through defaultDsl; no prefix. */
    record Untenanted() implements TenantIdSource {}

    /** Tenant key is uniform across a request (and therefore across any DataLoader batch). */
    sealed interface RequestScoped extends TenantIdSource {
        /** An @tenantId-marked GraphQL argument drives routing (root-field direct-SQL case). */
        record ArgSource(String argName, TypeName argJavaType) implements RequestScoped {}
        /** The request-scope `tenantId` contextArgument drives routing. */
        record ContextArgSource(String name) implements RequestScoped {}
    }

    /** Tenant key is per-source-object: a child inherits it from its arrived parent row's tenant column. */
    record SourceScoped(/* parent-row tenant-column ref */) implements TenantIdSource {}
}
```

The split is what makes the two DataLoader/federation consumers clean (see below): a loader-NAME prefix needs a value uniform across the batch, so the prefix consumer accepts `RequestScoped | Untenanted` and a `SourceScoped` reaching a prefix site is a *compile* error, not a runtime skip; the federation per-tenant grouping is exactly the `SourceScoped` case (the batch spans tenants, so it groups rather than prefixes). *(Implementation fork: a lighter alternative drops the `RequestScoped`/`SourceScoped` seal and carries only the binding, letting each consumer read scope off the already-present `source()` — `Source.Child` ⇒ per-row, `Source.Root`/`OnlyChild` ⇒ request-uniform. Pin the structural split unless threading `source()` to every consuming site proves cheaper; see Open questions.)*

Per-field classification, run only when `<tenantColumn>` is configured:

- A field with an `@tenantId`-marked argument ⇒ `ArgSource(argName, reflectedType)`.
- A field listing `tenantId` in its `@service(contextArguments:)` ⇒ `ContextArgSource("tenantId")`.
- A child field (`Source.Child` / `Source.OnlyChild`) whose arrived parent row backs a tenant-scoped table ⇒ `SourceScoped`.
- Any field whose target type sits on a global table, or that has no tenant key in scope ⇒ `Untenanted`.

The directive and the contextArgument are the two *request-scope* bindings; a field carrying both is rejected (two competing sources). `Untenanted` here is a genuine per-field fact (the field reaches global reference data), distinct from the whole-build single-tenant case where the axis is never computed.

### DataLoader name partitioning (re-add prefix conditionally)

Post-R190 every DataLoader name is `path` alone at five emission sites, each composing `$T.join("/", env.getExecutionStepInfo().getPath().getKeysOnly())`: `DataLoaderFetcherEmitter.buildDataLoaderName` (~`:157`), `TypeFetcherGenerator` (the batched-fetcher name composition, ~`:5832`), `MultiTablePolymorphicEmitter` (the batched connection / list fetchers, ~`:1113` / ~`:1182`), and `QueryNodeFetcherClassGenerator.dispatchNodes` (~`:156`). *(Line numbers as of 2026-06-26; anchor on the symbols, the files drift.)* R45 re-introduces the tenant prefix conditionally, driven by the field's `TenantIdSource`:

- **`RequestScoped` source + tenant-scoped target type:** name is `tenantKey + "/" + path`, where `tenantKey` is the stringified request-scope value read off the classified arm (the `@tenantId` arg value or the `tenantId` contextArgument). Cross-tenant cache collisions can't occur because each tenant's data lives in a distinct loader.
- **`Untenanted` (global target type, or `<tenantColumn>` unconfigured):** name stays `path` alone. Loaders for reference data shared across tenants stay shared.
- **`SourceScoped`:** does not reach the loader-name prefix; a batch spans tenants, so a single name-level prefix is wrong. This case routes through the federation-style per-tenant grouping (below) instead. The prefix consumer's signature accepts only `RequestScoped | Untenanted`, so a `SourceScoped` here is a compile error.

The five emission sites add a `TenantIdSource`-driven branch consulted at emit time to pick prefix vs no-prefix. The tenant-key read only appears in the prefix branch; sites emitting global names don't reference it at all.

### Federation entity dispatch grouping (re-introduce tenant level)

R190 collapsed the federation `_entities` dispatch grouping in `HandleMethodBody` from `Map<Integer, Map<String, List<Object[]>>>` to `Map<Integer, List<Object[]>>` when it removed `getTenantId`. R45 re-introduces the inner-tenant level when `<tenantColumn>` is configured and the entity's resolution is `SourceScoped` (each `_entities` representation carries its own tenant key, so a batch spans tenants by construction):

- The per-rep DFE construction stays as-shipped (R190 left `repEnv = newDataFetchingEnvironment(env).arguments(rep).build()` in place so any in-rep argument reads still work).
- The grouping reads the tenant key off the per-rep representation per the classified `SourceScoped` arm (the rep carries the tenant column), **not** via a `getTenantId(repEnv)` method call. The reworked design has no such method.
- The `groups` declaration (`HandleMethodBody:67`) widens back to `Map<Integer, Map<String, List<Object[]>>>`.
- The per-rep grouping call (`emitDecodeAndGroup`, ~`:133`) becomes `groups.computeIfAbsent(altIndex, k -> new LinkedHashMap<>()).computeIfAbsent(tenantKey, k -> new ArrayList<>()).add(...)`.
- `emitGroupDispatch` (~`:138`) re-adds the inner-entry iteration loop and reads bindings off the inner entry.
- The class-level javadoc at `HandleMethodBody` and the sister doc comments at `EntityFetcherDispatchClassGenerator` / `QueryNodeFetcherClassGenerator` re-add the per-rep tenant-key read in the per-rep DFE rationale.

When `<tenantColumn>` is unconfigured, the federation grouping stays at R190's collapsed shape (single-level `Map<Integer, List<Object[]>>`).

### Validation diagnostics

Rejections for the multi-tenant additions, all surfaced through `Rejection.AuthorError` arms and routed through `GraphitronSchemaValidator.validate` per R190's validator-mirrors-classifier pattern. The first three validate the `@tenantId` *directive*; the fourth validates the `TenantIdSource` *axis* (a classifier decision with no emitter arm must fail at validate time, not throw at emit/run time):

- `tenantIdArgTypeMismatch` — the `@tenantId`-marked argument's reflected Java type is not assignable to the configured `<tenantColumn><javaType>` (the `T` the `byTenant` overload binds).
- `duplicateTenantIdSource` — the same field carries both a `@tenantId` argument and lists `tenantId` in its `contextArguments`.
- `multipleTenantIdArgs` — more than one argument on the field is marked `@tenantId`.
- `tenantColumnAbsentOnParent` — a child field classified `SourceScoped` whose arrived parent row backs a type carrying no tenant column (the classifier reached the `SourceScoped` arm but the parent can't supply the key). *(Under the structural-split shape, the "`SourceScoped` at a loader-name prefix site" mismatch is a compile error and drops off the validator's plate; under the lighter binding-only shape it would need a fifth rejection.)*

All follow the typed-record-plus-`message()`-renderer pattern R190 pinned for `AuthorError.TypeConflict` (a record per arm carrying the structural data — `ConflictSite`, field name, arg names — plus a `message()` override; factories on `Rejection` mirroring `contextArgumentTypeConflict`). At most one `@tenantId` argument per field; the directive applies to ARGUMENT_DEFINITION only.

## Implementation

### Mojo configuration (`graphitron-maven/`)

- New `<tenantColumn>` element with `<name>` (column name) and `<javaType>` (FQN). Both required when the element is present; absence drives the single-tenant fallback path (R190's shape, unchanged).

### Catalog / classification (`graphitron/`)

- Tenant-scope classification during catalog loading: produces a `Set<TableName>` of tenant-scoped tables, plus the configured `T` as a `TypeName`. Stored on the build result for downstream consumers.
- New sealed `TenantIdSource` carrier (`model/TenantIdSource.java`), sibling to `Source` / `Operation` / `Target` but an optional overlay rather than a mandatory `OutputField` accessor: attach it as an optional component on `OutputField`, or a side map keyed by field coordinate, computed only when `<tenantColumn>` is configured. Arms per [Design](#tenantidsource-the-per-field-routing-axis): `Untenanted`, `RequestScoped` (`ArgSource` / `ContextArgSource`), `SourceScoped`.
- New per-field classifier step (runs only when `<tenantColumn>` is configured) populating `TenantIdSource` per the rules in Design.
- New rejections (typed `AuthorError` records with `message()` overrides per R190's `TypeConflict` precedent): `tenantIdArgTypeMismatch`, `duplicateTenantIdSource`, `multipleTenantIdArgs`, `tenantColumnAbsentOnParent`. Factories on `Rejection` mirror `contextArgumentTypeConflict`.
- Validator-mirror surface: `GraphitronSchemaValidator.validate` gains `validateTenantIdSources(schema, errors)`, draining the new rejection list into `ValidationError`s the same way R190's `validateContextArgumentTypeAgreement` does. No-drift follows R190's single-cached-producer mechanism: the `TenantIdSource` classification is computed once and cached on the build result, exactly as `ContextArgumentClassifier`'s output rides `GraphitronSchema.contextArguments` (a record component read by both the validator and the facade emitter rather than re-classified), so the validator-mirror is the build-time guarantee against classifier/emitter drift.

### Facade generation (`generators/schema/GraphitronFacadeGenerator.java`)

- The single-tenant overload R190 emits stays unchanged.
- When `<tenantColumn>` is configured, emit a second `newExecutionInput` overload with `Function<T, DSLContext> byTenant` slotted second (between `DSLContext defaultDsl` and the alphabetical contextArguments). Body: `Objects.requireNonNull(byTenant, "byTenant")` after the `defaultDsl` null-check, then `graphQLContext.put(<marker>, byTenant)` under a generated marker key (Q3) — the same `put` mechanism R190 uses for `defaultDsl`.

### `GraphitronContext` (`generators/util/GraphitronContextInterfaceGenerator.java`)

- **Unchanged.** R190's three-method sealed shape (`getDslContext(env)`, `getContextArgument(env, name)`, `getValidator(env)`) is the stable baseline; the reworked design adds no `getTenantId` / `getDslContext(T)` (see [Open questions](#open-questions) Q4 for the dropped widening design). The interface stays sealed + permits the singleton.

### Emission sites

- **Five DataLoader name sites:** branch on the field's `TenantIdSource` at emit time. A `RequestScoped` source with a tenant-scoped target emits `<tenantKey> + "/" + path` (the tenant key read off the classified arm — the `@tenantId` arg value or the `tenantId` contextArgument, stringified); `Untenanted` stays path-only (the R190 default). The prefix-emitting helper's signature accepts only `RequestScoped | Untenanted`, so a `SourceScoped` reaching it is a compile error.
- **Federation entity dispatch grouping (`HandleMethodBody`):** when the entity resolution is `SourceScoped` and `<tenantColumn>` is configured, widen the `groups` declaration back to the nested-map shape, read the per-rep tenant key off the representation (not via a method), restore the inner-entry iteration in `emitGroupDispatch`, and revise the class-level javadoc. The un-tenanted path stays at R190's collapsed shape.
- **`@tenantId`-arg dispatch (`ArgCallEmitter` / the field-fetcher emitter that wires the DSLContext):** at the field's SQL entry, read `byTenant` off `GraphQLContext` and emit `byTenant.apply(env.getArgument("customerCompanyId"))` so the field's DSLContext is the per-tenant one for the duration of the fetch. No `getDslContext(T)` method intermediates.

### Schema (`schema/directives.graphqls`)

- New `directive @tenantId on ARGUMENT_DEFINITION`. The shipping `directives.graphqls` already auto-injects per R190; the new directive lands on the same file.

### Re-enabling commented-out tests

R190 commented out two execution-tier tests in `graphitron-sakila-example` (`querydb/FederationEntitiesDispatchTest.java`'s `entities_multiTenancyPartition_oneSelectPerTenant` and `querydb/GraphQLQueryTest.java`'s `nodes_perTenantPartition_separateBatchPerTenant`) with a forward-reference comment naming this item. R45 uncomments both methods in the same commit window as the generator changes and reshapes the test fixtures: per-tenant `DSLContext` selection happens at request entry now (`Graphitron.newExecutionInput(defaultDsl, byTenant, ...)`), with each field's tenant key sourced through its classified `TenantIdSource` rather than a consumer-supplied `GraphitronContext` impl. Assertion shapes (`QUERY_COUNT == 2` for the partitioning case, the per-rep dispatch invariant for the federation case) stay as the canonical execution-tier proofs.

## Tests

- **Catalog (L1/L2).** Tenant-column classification: tables with the configured column tag tenant-scoped, others global. Mojo without `<tenantColumn>` ⇒ all tables global, no multi-tenant overload emitted, `TenantIdSource` axis never computed.
- **Classification (L2).** New `TenantIdSourceClassificationTest`: SDL fixtures exercise each `TenantIdSource` arm (an `@tenantId` arg ⇒ `ArgSource`; `tenantId` contextArgument ⇒ `ContextArgSource`; child off a tenant-scoped parent ⇒ `SourceScoped`; global target ⇒ `Untenanted`) and the four rejections (`tenantIdArgTypeMismatch` with a mismatching arg type, `duplicateTenantIdSource` with `@tenantId` + `tenantId` contextArgument on the same field, `multipleTenantIdArgs` with two `@tenantId`-marked args, `tenantColumnAbsentOnParent` with a `SourceScoped` child off a parent backing no tenant column). Each accept-case fixture asserts the typed `TenantIdSource` arm; each rejected fixture asserts both the typed record and the rendered `message()` shape.
- **Validation (L4).** New `TenantIdSourceValidationTest` under `graphitron/src/test/java/no/sikt/graphitron/rewrite/validation/`, mirroring R190's `ContextArgumentTypeAgreementValidationTest`: feeds each new `Rejection` into `validateTenantIdSources` and asserts the rendered `ValidationError` shape.
- **Pipeline (L4).** Extend the R190 `GraphitronFacadeGenerator` pipeline test (the case that snapshots the single-tenant factory) with a second case: same SDL plus `<tenantColumn>` configured. Snapshot asserts both overloads emit, the `byTenant` parameter sits second on the multi-tenant overload, the factory body puts `byTenant` on `graphQLContext`, and `GraphitronContext` is unchanged (no new methods — the R190 three-method shape holds). Loader-name partitioning snapshot covers both branches (`RequestScoped` + tenant-scoped target ⇒ prefixed, `Untenanted` ⇒ path-only). Federation grouping snapshot covers the nested-map re-introduction at `HandleMethodBody` for the `SourceScoped` case.
- **Compile (L5).** `graphitron-sakila-example` gains a multi-tenant schema fixture using both factory overloads, the `@tenantId` arg directive, and at least one global-scope reference-data fetch. Generated code passes `mvn compile -pl :graphitron-sakila-example -Plocal-db`.
- **Execute (L6).** R190's existing two commented-out tests (`entities_multiTenancyPartition_oneSelectPerTenant`, `nodes_perTenantPartition_separateBatchPerTenant`) uncomment and reshape against the new sealed surface. New execution-tier coverage: single-request multi-tenant routing through `byTenant`, loader-name partitioning verified by query count + the `DataLoader.getCacheKey` introspection, `@tenantId` arg routing verified by per-tenant table isolation.

## Open questions

1. **`@tenantId` syntactic form.** Currently drafted as a per-argument directive. Alternatives considered: sigil in argMapping value (`argMapping: "customerCompanyId: $tenantId"`); top-level directive arg (`@service(..., tenantIdArg: "customerCompanyId")`). Per-arg directive picked because the marker is a property of the argument, local to its definition, and the existing `argMapping` slot's "GraphQL arg → Java param name" semantics stays single-purpose. Revisit if R46's fan-out cousin (analogous "this arg drives fan-out") finds the sigil shape natural and the per-arg-directive vs sigil divergence becomes friction.
2. **Multi-tenant overload when the reachable graph has no tenant-scoped types.** Currently emitted whenever `<tenantColumn>` is configured. Alternative: skip emission if classification finds no tenant-scoped reachable types. The conservative emission is harmless; revisit only if it causes IDE noise.
3. **Marker key for `byTenant` on `GraphQLContext`.** The multi-tenant factory puts the `Function<T, DSLContext>` under a typed key the dispatch site reads back. Options: a generated marker class adjacent to `GraphitronContextImpl`, or a structural `Class<? extends Function>` literal. Pick during implementation; the consumer-facing API is unchanged either way. *(Note: this does **not** dissolve under the resolved Q4 — `byTenant` still travels on `GraphQLContext`. Only the `getDslContext(T)` method that used to read it back is gone; the read is now inline at the dispatch site, the same family as R190's `getDslContext(env)` default reading `defaultDsl`.)*
4. **`TenantIdSource` scope split: structural vs binding-only.** *Open implementation fork.* The Design pins the structural shape: `TenantIdSource` carries a sealed `RequestScoped` / `SourceScoped` split so the loader-name-prefix consumer's signature accepts only `RequestScoped | Untenanted` and a `SourceScoped` at a prefix site is a compile error. The lighter alternative drops the scope seal, carries only the binding (`ArgSource` / `ContextArgSource` / `RowColumn` / `Untenanted`), and lets each consumer read scope off the already-present `source()` (`Source.Child` ⇒ per-row, `Source.Root` / `OnlyChild` ⇒ request-uniform). The structural split makes the legality compile-checked (no runtime `instanceof` rejection) at the cost of one more seal level; the binding-only shape is lighter if `source()` is reliably threaded to every consuming site. Pick during implementation; the structural split is the default unless the threading proves cheaper. *(Under binding-only, the validator gains a fifth rejection for "`SourceScoped` binding reaching a loader-name prefix site" that the structural split makes unrepresentable.)*
5. **`SourceScoped` parent-row column reference shape.** The `SourceScoped` arm names how a child field reads its tenant key off the arrived parent row. The exact carrier (a `ColumnRef` into the parent's backing, an accessor ref, or a reuse of an existing `SourceKey.Reader`-adjacent shape) is unresolved; it interacts with the parent row's backing classification (catalog `Record` vs `@service` DTO). Resolve against the live `SourceKey` / `ColumnRef` surfaces at implementation time.

**Resolved (2026-06-26): adopt `TenantIdSource`, leave `GraphitronContext` unchanged.** The 2026-05-20 draft layered routing by widening the sealed `GraphitronContext` with `T getTenantId(env)` and `DSLContext getDslContext(T tenantId)`, stashing `byTenant` under a marker key. The prior Q4 flagged this on three principle-grounds, all confirmed by a 2026-06-26 principles pass:

- `getDslContext(T)` is structurally a thunk over `byTenant`, not a context method — dropped; the dispatch site reads `byTenant` inline.
- `getTenantId(env)` returning `null` on the single-tenant path is a trans-axis return shape — dropped; the per-field tenant key is the classified `TenantIdSource` arm, never a nullable method return.
- Stashing `byTenant` on `GraphQLContext` was called a wire-format leak. The principles pass **cleared** this one: `byTenant` rides the same typed-key mechanism R190 already uses for the accepted per-request `defaultDsl`, and the function never enters the classified model (no sealed arm carries it), so it is not the wire-format-leak family. The leak finding over-fired; what survived was the `getDslContext(T)` call shape, which is dropped.

The resolved design is what the Design and Implementation sections above describe: a per-field sealed `TenantIdSource` overlay (sibling to R316's `source` / `operation` / `target`, computed only when `<tenantColumn>` is configured), consumed by the five DataLoader name sites and the federation grouping; `GraphitronContext` keeps R190's three-method shape. R190 doesn't change either way — its removal of `getTenantId(env)` is exactly the ground this stands on. The remaining live forks are Q3 (marker key), Q4 (scope split), and Q5 (`SourceScoped` carrier).

## Roadmap entries (siblings / dependencies)

- **Depends on R190** (`single-tenant-execution-input-factory`, **Done** — file deleted on completion, recorded in [`changelog.md`](changelog.md); `depends-on:` stays `[]` because a completed item carries no live roadmap slug to link without dangling). The single-tenant factory, sealed `GraphitronContext`, the call-site-cast `getContextArgument`, cross-site type-agreement check, `Rejection.contextArgumentTypeConflict`, the validator-mirrors-classifier wiring, the consumer-migration baseline in `graphitron-sakila-example`, and the user-doc revision pass all shipped under R190. R45 is the strictly-additive multi-tenant slice on top of that baseline.
- **Reshapes** [`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md) (R46). R46's prior design built on a publicly-implemented `ContextValueRegistration<FanOut>` permit — which R190 already dissolved when it sealed the interface. R46 will be redesigned: the fan-out source becomes a list-typed `contextArgument` (e.g. `["fnr", "tenantIds"]` with `List<Long> tenantIds` on the factory R190 lands) plus a new directive arg (`fanOutOver:`) or per-arg directive (`@fanOut`). R45 leaves the hooks for R46: list-typed factory parameters carry the reflected parameterised type unchanged, and emission makes no scalar-only assumption about contextArguments.
- **Independent of** [`helper-emission-non-fetcher-hosts.md`](helper-emission-non-fetcher-hosts.md) (R85). The 2026-05-20 draft listed R85 as affected because the design widened `GraphitronContext` with new methods the host-class helper would ride; the resolved design adds no `GraphitronContext` methods (tenant routing is the classified `TenantIdSource` axis plus the `byTenant` factory slot), so the R85 helper gate is untouched.
- **Coordinates with** [`dslcontext-on-condition-tablemethod.md`](dslcontext-on-condition-tablemethod.md): both touch `ArgCallEmitter`'s param walk, and both choose a per-request `DSLContext` for a field — adjacent emission paths. No shared file edits expected, but reconcile the DSLContext-selection site if both land near each other.
- **Independent of** [`service-short-classname-resolution.md`](service-short-classname-resolution.md).
