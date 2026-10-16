---
id: R1051
title: "Name the @defaultTenant entity tenant apart from the global-read default, including none"
status: Ready
bucket: feature
depends-on: []
created: 2026-10-16
last-updated: 2026-10-16
---

# Name the @defaultTenant entity tenant apart from the global-read default, including none

## Goal

A database-per-tenant consumer can name, per request, which tenant `_entities` uses for types marked `@defaultTenant` separately from the default tenant that serves global-table reads, and can name *no* entity default tenant while global reads still have one. With no entity default tenant, every representation of a `@defaultTenant` type answers `null` at its own position without an error and without opening a connection, as a request with no default does today; other entity types and all global reads are unaffected. When the consumer names nothing separately, entities keep using the request default, so existing callers see no change.

In consumer code the whole change is one call on the value the generated factory already takes:

```java
// A caller with a home institution: unchanged, entities and global reads both use it.
RequestTenants.withDefault(permitted, home);

// A caller without one: global reads still have a database, @defaultTenant entities answer null.
RequestTenants.withDefault(permitted, globalReadTenant).withoutEntityDefault();
```

Terms used below. The *request tenant set* is the set of tenants one request may touch, passed to `Graphitron.newOwnedExecutionInput` as a generated `RequestTenants` value. The *default tenant* is the optional member of that set whose database answers graphitron's reads of global tables (tables without the tenant column), instead of the runtime's default source. The *entity default tenant*, new here, is the optional member of the set on which `_entities` looks up the representations of a `@defaultTenant` type, a federation entity whose `@key` cannot carry the tenant column. The name says what it is: the default tenant for those entities, which is the request's default tenant unless the request names another or none, so the directive's name stays literally true and the role plainly applies only where a key names no tenant (global entities and entities whose key carries the tenant column never read it).

Reported from the sis port. sis's `FeideBruker` is `@table(name: "PERSON") @key(fields: "brukernavn") @defaultTenant`: a username unique only within one institution, resolved on the caller's home institution. Callers with no home institution (machine users, calls outside access control) must get `null` for every `FeideBruker` representation, because guessing an institution can return another person's row for the same username: a privacy guarantee v9 gave and sis pins with an IT. Today one value serves both roles, so sis can either name no default (entities `null`, correct, but every global read goes to the default source, which sis does not have) or name some other institution (global reads work, `FeideBruker` resolves on an institution the caller does not belong to). Neither reaches v9 parity. Constraints from the request: `null` without an error (the router treats an unknown entity as normal), answers keep their batch positions, other entity types unaffected.

## User documentation (first-client check)

The two pages that change, as they will read.

**`docs/manual/how-to/tenant-scoping.adoc`, § 2, a new paragraph after "Reading global tables from a tenant database"** (and the sentence there listing what the default tenant covers ends "... and `node`, `nodes` and `_entities` for global types", dropping the `@defaultTenant` clause, which moves here):

> *Naming the tenant for `@defaultTenant` entities.* `_entities` looks up a type marked xref:../reference/directives/defaultTenant.adoc[`@defaultTenant`] on the request's _entity default tenant_. Unless you say otherwise it is the default tenant, so `RequestTenants.withDefault(permitted, home)` serves both. Name it separately when the tenant that serves global reads is not one whose rows the caller may be matched against:
>
> ```java
> RequestTenants.withDefault(permitted, globalReadTenant).withEntityDefault(home);   // entities on home
> RequestTenants.withDefault(permitted, globalReadTenant).withoutEntityDefault();    // entities answer null
> ```
>
> With no entity default tenant, every representation of a `@defaultTenant` type answers `null`, without an error and without opening a connection for it, and the rest of the batch resolves normally; global reads still use the default tenant. An entity default tenant follows the rules a default tenant does: it must be in the request's tenant set, or `withEntityDefault` throws, and this runtime must have a `DataSource` for it, or the request fails before any field runs. Fields below a resolved entity read from the entity default tenant when they are tenant-scoped, and from the default tenant when they read global tables, exactly as below any other tenant-routed row. An entity default tenant needs no default tenant: `RequestTenants.of(permitted).withEntityDefault(home)` reads global tables from the default source.

**`docs/manual/reference/directives/defaultTenant.adoc`**, the description line and § What it does:

> *`@defaultTenant`* resolves a federation entity on the request's entity default tenant when its key cannot name a tenant, in a build with database-per-tenant routing (`<tenantColumn>`).
>
> Each representation of the type is then looked up on the request's entity default tenant, which is its default tenant unless the request names one separately (see xref:../../how-to/tenant-scoping.adoc[Tenant scoping], "Naming the tenant for `@defaultTenant` entities"). [...]
>
> A request that names no entity default tenant, either because it names no default tenant or because it said `withoutEntityDefault()`, answers every representation of a marked type with `null`, without an error, and resolves the rest of the batch normally; no tenant connection is opened for the type. A request whose entity default tenant this runtime has no `DataSource` for fails before any field runs.

The `@defaultTenant` SDL description in `graphitron-model`'s `directives.graphqls` gets the same "entity default tenant, which is the default tenant unless the request names one separately" wording. The directive keeps its name; see Other solutions.

## Implementation

`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/util/ConnectionRuntimeClassGenerator.java`:

- `requestTenants(...)`, the emitted `RequestTenants` value class, gains a third field `Optional<K> entityDefaultTenant`, held resolved rather than tri-state: the factories decide what "unset" means, so the stored value never needs an "inherit" marker.
  - New canonical constructor `RequestTenants(Set<K> tenants, Optional<K> defaultTenant, Optional<K> entityDefaultTenant)`. It canonicalises the entity default tenant through `canonicalTenantKey` and refuses one outside the set with `IllegalArgumentException("Entity default tenant '<k>' is not in the request tenant set.")`, the default's check repeated for the second role.
  - The existing public two-argument constructor stays and delegates with `entityDefaultTenant = defaultTenant`. `of(tenants)` and `withDefault(tenants, d)` keep their signatures and, through it, their behaviour: `of` names neither role, `withDefault` names `d` for both.
  - New instance methods `withEntityDefault(K tenant)` and `withoutEntityDefault()`, each returning a new value with the same set and default tenant and only the entity role changed. They go through the canonical constructor, so the membership check runs there and nowhere else.
  - New accessor `entityDefaultTenant()`; `equals`, `hashCode` and `toString` include it; the class javadoc names both roles.
- The `TenantConnections` carrier gains `entityDefaultTenant`, copied from `requestTenants.entityDefaultTenant()` in the constructor beside `defaultTenant`. `dslGlobal()` keeps reading `defaultTenant`: every global read, including `_entities` for global types and global `@splitQuery` children below a `@defaultTenant` entity (the fixture's `Inventory.store`), still reads the default tenant.
- `staticDefaultTenant` (`TenantConnections.defaultTenant(DataFetchingEnvironment)`) becomes `staticEntityDefaultTenant`, emitting `TenantConnections.entityDefaultTenant(env)`, which returns `of(env).entityDefaultTenant`. The entity dispatcher is the old method's only reader, and it now wants the entity role, so the old method is removed rather than kept unread.

`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/util/GraphitronConnectionInstrumentationGenerator.java`: the hosting check that refuses an unhosted default tenant before any fetcher runs is widened to cover both named roles at the one site, so the invariant keeps a single enforcer: each present role is checked against `runtime.tenantKeys()`, and the message names the role that failed ("Default tenant '<k>' has no DataSource ...", as today, or "Entity default tenant '<k>' has no DataSource in this runtime; name an entity default tenant this runtime hosts."). The reasoning in the existing comment carries over unchanged: the value is the consumer's choice, never the client's, so there is no hosting probe to hide.

`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/util/HandleMethodBody.java`: the read-once statement for an alternative routed by the request reads `TenantConnections.entityDefaultTenant(env)` into a local named `entityDefaultTenant`, and the `continue` for an absent one, the extra `VALUES` cell and the grouping key read that local. Grouping, the tenant-set skip, per-group `dslFor` acquisition and the `localContext` hand-down are untouched, so a rep resolved on an entity default tenant that differs from the default hands that entity default tenant to its tenant-scoped subtree with no further change.

`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/TenantBinding.java`: `EntityRepBound.TenantSource.RequestDefault` is renamed `RequestEntityDefault` and its javadoc says "the request's entity default tenant". Rename only; its readers in `TenantBindingIndex` (the fold that mints it), `HandleMethodBody`, `SelectMethodBody`, `TenantBindingClassificationTest` and `TenantFanOutClassificationTest` follow. The classifier's verdicts do not change: which alternatives route by the request is a schema fact, and which tenant the request names for them is a runtime fact this item adds.

Docs: the two manual pages and the SDL description, as drafted above.

## Tests

- `graphitron-sakila-example`, `TenantDivinedRoutingExecutionTest`, beside the three existing `defaultTenantEntity_*` cases over the federated `Inventory` in `multitenant.graphqls`, one operation selecting the global root field `languages` together with `_entities`:
  - `defaultTenantEntity_withoutEntityDefault_answersNull_whileGlobalReadsKeepTheDefault`: `withDefault({1, 2}, 1).withoutEntityDefault()` over an `Inventory` rep, a key-routed `FilmActor` rep on tenant 2, and a second `Inventory` rep. Both `Inventory` slots are `null` with no errors, `FilmActor` resolves, `languages` is answered, tenant 1 is opened once (for `languages` alone), tenant 2 once, the default source never. This is the sis machine-user case.
  - `defaultTenantEntity_namedEntityDefault_resolvesThere_andItsGlobalChildReadsTheDefault`: `withDefault({1, 2}, 2).withEntityDefault(1)` (the fixture's inventory rows all live in tenant 1's database) over inventory 1 and `foreignInventoryId`, selecting `film { title }` and `store { storeId }`. Inventory 1 resolves on tenant 1 with "Tenant One Film" while its global `store` child and `languages` are read on tenant 2; `foreignInventoryId` answers `null` because its row names film 2; tenant 1 and tenant 2 are each opened once, the default source never.
  - `entityDefaultTenant_outsideTheSet_isRefusedWhereTheValueIsBuilt`: `withDefault({1}, 1).withEntityDefault(2)` throws `IllegalArgumentException` naming the entity default tenant.
  - `entityDefaultTenant_notHostedByThisRuntime_failsBeforeAnyDatabaseIsOpened`: `withDefault({1, 2, 99}, 1).withEntityDefault(99)`, the existing `defaultTenant_notHostedByThisRuntime_*` case's shape, asserting the entity-default message and zero connections.
  - `defaultTenantEntity_entityDefaultWithoutADefaultTenant_globalReadsTakeTheDefaultSource`: `RequestTenants.of({1, 2}).withEntityDefault(1)` over inventory 1 with `store { storeId }`, plus `languages`. Inventory 1 resolves on tenant 1 while `store` and `languages` are read on the default source; tenant 1 and the default source are each opened once, tenant 2 never. This is the one admitted state the cases above leave without an executing witness.
  - The three existing `defaultTenantEntity_*` cases stay as they are; passing unchanged is the evidence that a request naming nothing separately sees no change.
- `graphitron`, `TenantRuntimeKeyTypeTest`: the existing `RequestTenants` shape assertions extend to the three-argument constructor, `entityDefaultTenant()`, and the two instance withers with their parameter lists; the carrier assertions name `entityDefaultTenant(DataFetchingEnvironment)` and assert `defaultTenant(DataFetchingEnvironment)` is gone.
- Classification tests change only by the rename; no new classification case, since no verdict changes.

## Retired vocabulary

- `TenantConnections.defaultTenant(DataFetchingEnvironment)` (the static accessor; the carrier's `defaultTenant` field and `RequestTenants.defaultTenant()` stay)
- `TenantSource.RequestDefault`, and the test names carrying it: `TenantBindingClassificationTest`'s two `...ByTheRequestDefault...` methods and `TenantFanOutClassificationTest.markerOnAnEntityRoutedByTheRequestDefaultRejects`, renamed to `...ByTheRequestEntityDefault...`
- "resolves on the request's default tenant" and like phrasings for `@defaultTenant` in prose, which now resolves on the entity default tenant

## Other solutions we've considered

- **The reporter's `withEntityTenant(Optional<K>)`, one method for both cases.** It works, but an `Optional` parameter reads worse at the call site than two named methods, and sis's two call sites each know which case they are in. The canonical three-argument constructor does take positional `Optional`s, deliberately: it is the record-style canonical constructor, mirroring the field types as the existing two-argument one already does, and the named factories and withers are the call-site surface.
- **Name the role "entity tenant".** Shorter, but it suggests every entity type uses it, when global entities and key-routed entities never do, and it splits the vocabulary from the directive, so every doc would need a gloss bridging `@defaultTenant` to "entity tenant".
- **Replace `withDefault` with a two-role builder** (`RequestTenants.of(set).globalReadsOn(t).entitiesOn(t)`). It models the two roles symmetrically, but it changes a shipped API sis already calls, and the common case, one institution serving both, is exactly what `withDefault` says. The value class holds two roles either way; only the factory surface differs.
- **Rename `@defaultTenant`.** Not owed: under the role name chosen above the directive still says what it does, and renaming a directive sis's schema already carries would cost a migration for no gain.
- **An unhosted or out-of-set entity default tenant answers `null` instead of failing.** Rejected for consistency with the default tenant: both are the consumer's choice, never the client's, so a bad one is a wiring bug that should fail loudly before any SQL, not a silent `null` that looks like a missing user.
- **Consumer-side: sis wraps `_entities` and nulls `FeideBruker` representations itself.** It reaches into generated dispatch and re-implements position bookkeeping graphitron already owns, and every other database-per-tenant consumer with a username-shaped key would repeat it.
