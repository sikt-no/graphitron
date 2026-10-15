---
id: R1038
title: "A client-supplied tenant key routes by the tenant column database value, not its converted Java form"
status: Spec
bucket: bug
priority: 3
theme: runtime-connection
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# A client-supplied tenant key routes by the tenant column database value, not its converted Java form

## Goal

In a database-per-tenant build, two tenant values that the tenant column stores as the same database value name the same tenant, wherever the value came from. Today the generated router compares tenant keys by their Java form, so when a jOOQ converter maps the column to a Java type with more spellings than the database type, a client value that binds correctly in the SQL is refused by the router or silently matches no tenant. sis's `INSTITUSJONSNR_EIER` is `INTEGER` in Oracle and `String` in Java through `IntegerToStringConverter`: `eierOrganisasjonskode: "0194"` binds as `194`, yet routing fails with "Tenant '0194' is not permitted for this request" because the request's tenant set holds `"194"`. After this lands, every tenant key graphitron receives, from a client argument, a decoded id, a `@tenant` value, a filter slot, the consumer's `RequestTenants` or the runtime's `DataSource` map, is put through the column's converter and back before it is compared, so `"0194"` and `"194"` are one tenant. A value the column cannot hold (`"abc"` on that column) is a client error. Builds whose tenant column has no converter route as today, except that an unreadable tenant value becomes a client error rather than a server error.

(Terms: the *tenant column* is the column named by `<tenantColumn>`, which decides which database a row lives in; a *tenant key* is one value of it, used to pick the `DataSource`; *routing* is choosing that `DataSource` for a field; *canonical* here means the Java value a read of the column would return for that database value.)

The schema shape, from the converter-backed fixture this item adds (the sis shape: a numeric column the catalog binds to `String`):

```graphql
type Query {
    sites(ownerOrg: String! @field(name: "owner_org")): [OrgSite!]!
    sitesNarrowable(ownerOrg: String @field(name: "owner_org")): [OrgSite] @tenantFanOut
}
```

With tenant `"186"` hosted and permitted, `sites(ownerOrg: "0186")` is refused today and returns tenant 186's sites after; `sitesNarrowable(ownerOrg: "0186")` returns an empty list today, presented as complete, and returns tenant 186's sites after.

## Evidence

Reported from sis on 2026-10-15 (fs-plattform branch `sis/upgrade-graphitron`). sis already normalises its own side: `GraphqlEngines` builds the runtime source map and `RequestTenants` with `replaceFirst("^0+", "")`, a hand copy of the converter's equality. In Graphitron 9, sis's `QueryInspector.identifiserInstitusjonsnrEier` stripped leading zeros from every tenant it derived before routing, so `"0194"` and `"194"` were the same institution. Whether clients rely on this is not verified from traffic; the defect stands without it, because the SQL and the router disagree about one value.

## Where keys meet today

All of this is emitted by `ConnectionRuntimeClassGenerator` into the consumer's `schema` package. The tenant key type `K` is the column's Java type, read off the catalog by `TenantScopeClassifier` into `TenantScopes.Configured.tenantType`.

- **Client-sourced keys** all pass through `TenantConnections.divinedTenant(Object...)`, which folds every bound value with `agreeOnTenant` (raw `equals`, before any coercion) and then types the agreed value with `tenantKeyOf`. `tenantKeyOf` is already the shared coercion seam: its javadoc says it is "shared by the routing fold and the fan-out narrowing so a value names the same tenant whichever reads it", and it parses a `String` for an `Integer`/`Long` key. The fan-out narrowing (`fanOutDomain(env, Object[])`) calls `tenantKeyOf` per named value and then `retainAll`s against the request domain, which is where `"0194"` silently empties the result. The per-row lookups (`HandleMethodBody.emitGroupDispatch`, for `node`/`nodes`/`_entities`) group on the raw decoded value and call `divinedTenant` per group.
- **Keys compared** after that: `entryFor`'s `tenants.contains(key.get())` (the one enforcer every keyed acquisition goes through), `permits`, `fanOutDomain`'s hosted/requested intersection, and the runtime's `sourcesByTenant.get`.
- **Consumer-supplied keys**: the `GraphitronRuntime` constructor map (copied into `sourcesByTenant`), and `RequestTenants` (tenant set and default tenant).
- **Keys read off rows** (`handDown`, the parent-row read in `TenantDslEmitter`, `tenantLoaderName`) are the converter's `from` of a database value, canonical by construction.

So the gate is not one site: the agree-fold and the narrowing compare before `entryFor` ever runs. The one site every client-sourced leaf already passes through, or can be made to, is `tenantKeyOf`. Consumer-supplied keys enter at two constructors.

## Implementation

**Capture: the column's database type and converter are catalog facts.** `JooqCatalog` reads two more facts off each live `Field` in the one place it already reduces columns (`findColumn` and `columnFactsOf`): the *database type*, the Java type of the value jOOQ exchanges with the database (`field.getConverter().fromType()`, equal to the bound type when there is no converter), and the *converter class*, the converter's class name, absent for jOOQ's identity converter. `ColumnEntry` and `ColumnFacts` gain both, and `JooqFactCapture` writes them to `sql_column` as `database_type` and a nullable `converter_class`, with column comments in the style of `binding_type`'s: like it, they are read off the live `Field` during the catalog walk and unrecoverable afterwards. The relation's owner, grain, mark and sweep are unchanged; two columns join an existing captured family. Other readers that reason about converter-backed columns (the node-id landing defects, hover) can use them later; this item reads them only in the classifier.

**Classification: one representative column, and an agreement that makes it representative.** `TenantScopeClassifier.classify` reads the two new facts off `ColumnEntry`, as it reads `columnClass` today. `TenantScopes.Configured` gains the representative column as the existing `TableRef` and `ColumnRef` (`CatalogRefs` already renders a column from those): the first tenant-scoped table in the catalog's schema-then-table order, the order `tenantScopedTables` already keeps.

One representative is only sound if every table's column compares values the same way, so `TenantColumnTypeDisagreement` widens from the bound type to the triple (bound type, database type, converter class). `TableSite` gains the two facts, the distinct-key set keys on the triple, and the message changes from "disagreeing Java types" to naming all three per table. This compares Java types and converter identity, not `sql_type`, so `varchar` beside `text`, or `NUMBER(4)` beside `NUMBER(5)` under one converter, still agree, while a `varchar` bound to `String` beside a `bigint` converted to `String` is rejected, as is one tenant column meeting two converter classes. The residual gap is two converters jOOQ instantiates from the same class with different logic, which is what codegen's lambda-converter form produces; the classifier compares what it can capture, and a configured `<converter>` class, sis's case, is fully covered.

**Runtime: one round trip, at every entry, in every multi-tenant build.** `TenantConnections` gains `public static K canonicalTenantKey(Object value)`: the value coerced through the representative column's `DataType` and then put through its converter and back, `converter.from(converter.to(type.convert(value)))`. The `DataType` and converter are read once into static fields from `<Tables>.<TABLE>.<COLUMN>`, with a private generic helper capturing the converter's wildcard so the round trip type-checks in Java 17, and the class carries the same `@SuppressWarnings({"deprecation", "removal"})` `NodeIdEncoder` carries for `DataType.convert`. A value it cannot read throws `IllegalArgumentException` naming the value. It is emitted in every multi-tenant build: for a column without a converter the round trip is jOOQ's identity converter, so there is one emitted shape and no predicate for an emitter to fork on, at the cost of a virtual call per key. It is public so a consumer can key its own tenant state by the same equality graphitron uses.

- `tenantKeyOf` becomes a call to `canonicalTenantKey` that turns its `IllegalArgumentException` into the generated client-error type: "Tenant value '<v>' is not a value of the tenant column." The coercion through the `DataType` replaces today's hand-written `Integer`/`Long` parse, so the wire-to-key rule now comes from the column, as the node-id decode's does. This settles the unreadable-value question for every path, `"abc"` and today's `NumberFormatException` alike: a client error, raised before the request-set check, so it reveals nothing about which tenants exist.
- `agreeOnTenant` is typed on `K` (`K agreeOnTenant(K current, Object candidate)`) and puts each leaf through `tenantKeyOf` before comparing, so `"0194"` beside `"194"` (two slots, a list argument, a batch) agrees instead of raising "Tenant bindings disagree". `divinedTenant` returns the fold's `K` with no cast and no second coercion; the disagreement message prints canonical values. Keys read off a parent row also pass through this fold and pay one round trip per fetch, which is cheaper than a second entry point every emitter would have to choose correctly.
- `RequestTenants`' constructor canonicalises the set and the default tenant before the membership check, so `withDefault(Set.of("0194"), "194")` is valid and `tenants()` returns canonical keys.
- `GraphitronRuntime`'s canonical constructor, which every other form delegates to, canonicalises the map's keys into `sourcesByTenant` in configured order, and throws `IllegalArgumentException` naming both keys when two canonicalise to one tenant. `tenantKeys()` and the mount's `Optional<K>` tenant then carry canonical keys. This renames the consumer's keys where they were not canonical; the manual says so and points at `canonicalTenantKey` for state the consumer keys itself.

With both ends canonical, `entryFor`, `permits`, `fanOutDomain`, `timedOutTenants` and the source lookup compare like with like and do not change. The per-row grouping in `HandleMethodBody` keeps grouping on the raw decoded value: two spellings of one tenant become two groups that canonicalise to one key and run on the one pinned connection, which is correct, so it is left alone.

**Behaviour for an unconverted column** is today's routing with two refinements a client can observe: a tenant value that does not parse is a client error rather than a server error, and a value `DataType.convert` accepts that the old parse did not (a `Long` for an `Integer` column) is coerced rather than refused as the wrong Java type.

**Known divergence, handed to a follow-up.** `NodeIdEncoder.requireColumnAgreement` defines two writers to one column as agreeing when `type.convert(a)` equals `type.convert(b)`, which for a converter-backed column compares the Java spellings, the equality this item retires from routing. After this lands, `"0186"` and `"186"` name one tenant but still conflict as two writes to one `owner_org`-shaped column. Moving that check onto the same round trip needs its own two-writer fixture on a converter-backed column and is filed as the Backlog item "Two-writer column agreement compares converter-backed values by their database value", which also decides whether the round trip becomes one emitted primitive both classes share.

## User documentation (first-client check)

In `docs/manual/how-to/tenant-scoping.adoc` §1, the sentence "The tenant key's Java type is read off the catalog (all tables must agree) …" keeps its first half, and its agreement clause becomes:

> All tables carrying the column must agree on its Java type, on the database value behind it and on any converter between them, so a column a converter maps to `String` on one table cannot be a plain `varchar` on another.
>
> Tenant keys are compared by the value the column stores. When a converter maps the column, graphitron puts every key through it and back before comparing: with an `INTEGER` column mapped to `String`, a client sending `"0194"` reaches the same tenant as one sending `"194"`, exactly as the SQL does. The keys you supply get the same treatment. Register your `DataSource` map and build `RequestTenants` with whichever spelling you have, and graphitron reduces them to the column's form; your mount, `tenantKeys()` and `RequestTenants.tenants()` report that form. To key your own state the same way, call `TenantConnections.canonicalTenantKey`. Two map keys that name one tenant are refused when the runtime is built. A value the column cannot hold, such as `"abc"`, is a client error.

## Tests

- **Capture** (`graphitron-model`, at the tier `ColumnFacts` capture is already pinned): `converter_org.org_code` captures database type `java.lang.Long` and converter class `OrgCodeStringConverter`; `film.film_id` captures database type equal to its binding type and no converter class.
- **Classification** (`TenantScopeClassifierTest`, unit tier, real fixture catalog): `classify(catalog, "issuer_org")` rejects with `TenantColumnTypeDisagreement`, naming `org_site` (bound `String`, database `Long`, `OrgCodeStringConverter`) against `plain_org_permit` (bound `String`, database `String`, none), the shape today's bound-type-only check passes. (`org_code` cannot serve: it is also a plain `bigint` bound to `Long` on several tables, so it already fails on the bound type.) `classify(catalog, "owner_org")` is `Configured` with the representative column on `org_site`; the existing `tenant_id` case is `Configured` with its first carrying table as representative.
- **Emission** (`TenantConnectionsGeneratorTest`, structural only): a multi-tenant `TenantConnections` declares a public static `canonicalTenantKey`; a single-tenant one does not. Behaviour is pinned at the execution tier, not by asserting method bodies.
- **Fixture.** `graphitron-sakila-db` `init.sql` gains two tables. `org_site` (`site_id serial` primary key, `owner_org org_code_domain NOT NULL`, `issuer_org org_code_domain`, `site_name`), seeded with sites for `186` and `1120`; the existing `org_code_domain` forced type maps both columns through `OrgCodeStringConverter` (`bigint` to `String`, so `"0186"` becomes `186` becomes `"186"`), the sis shape. `plain_org_permit` (`permit_id serial` primary key, `issuer_org varchar(20)`), the plain partner for the agreement case. `owner_org` and `issuer_org` appear on no other table. `graphitron-sakila-example` gains a generate execution with `<tenantColumn>owner_org</tenantColumn>` over a small `converted-tenant.graphqls` (the two fields in the goal plus `sitesIn(ownerOrg: [String!]! @field(name: "owner_org"))`), compiled at release 17 like the other fixtures.
- **Execution** (`ConvertedTenantRoutingExecutionTest`, beside `TenantDivinedRoutingExecutionTest`, with its counting `DataSource`s): with `{"186": …, "1120": …}` hosted and `RequestTenants.of(Set.of("186"))`:
  - `sites(ownerOrg: "0186")` returns 186's sites and opens only 186's source (fails today with "not permitted");
  - `sitesNarrowable(ownerOrg: "0186")` returns 186's sites, asking only 186 (returns an empty list today);
  - `sitesIn(ownerOrg: ["0186", "186"])` routes to 186 (fails today with "Tenant bindings disagree");
  - a runtime built with `{"0186": …}` and `RequestTenants.of(Set.of("0186"))` serves `sites(ownerOrg: "186")`, and its mount receives `"186"`;
  - `sites(ownerOrg: "abc")` is a client error and opens no connection;
  - a runtime built with both `"0186"` and `"186"` as keys throws `IllegalArgumentException` at construction.
- **No regression on the identity path.** `TenantDivinedRoutingExecutionTest` (the `Integer` `film_id` fixture, no converter) stays green unchanged apart from any case that pinned a non-numeric tenant value as a server error, which moves to the client error.

## Other solutions we've considered

- **Canonicalise only in `entryFor`.** It is the one enforcer for acquisition, but the agree-fold and the fan-out narrowing compare keys before it runs, so `"0194"` beside `"194"` would still disagree and a narrowed fan-out would still come back empty.
- **`DataType.convert` alone**, the rule `requireColumnAgreement` uses. It coerces a value *to* the bound type and leaves a value already of that type as it is, so for a column converted to `String` it never reaches the database type and canonicalises nothing. It is the first step of the round trip, not a substitute for it.
- **Emit the round trip only for a converter-backed column**, behind a flag on `Configured`. It saves one virtual call per key and costs a predicate every emit site re-tests, a field that means something only when the flag is set, and a second emitted shape to pin.
- **Read the database type off the live `Field` inside the classifier.** The classifier holds only `JooqCatalog`'s reduced `ColumnEntry`; reaching past it would put a live-catalog read outside the one reduction that owns them, for a fact other readers also want.
- **Require canonical consumer keys and validate them**, refusing `"0194"` in the source map rather than reducing it. The consumer can compute the canonical form (through its own column's converter, or `canonicalTenantKey`), so this is a real option; it loses because canonicalising at the constructors is an ordinary boundary decode, it lets sis delete its hand copy of the converter's equality outright, and the renaming it implies is reported through `tenantKeys()` and the mount rather than hidden.
- **A consumer-supplied normalisation hook.** It restates a rule the converter already states, and a consumer who forgets it gets today's behaviour.
- **Compare `sql_type` in the agreement check**, as the multitable ordering rule in `FieldBuilder` does. That rule needs one SQL type per position to order by; routing needs one equality, and comparing type names would reject `varchar` beside `text`, a common and harmless spread.
- **Leave it to the consumer**, with sis documenting a breaking change. The build would keep a state where the SQL and the router disagree about one value, which is the defect itself.

## Reviewer findings

### Round 1 (2026-10-15, Spec -> Ready, reviewer session 01K4dGN7wytRaSVmEM1jJuim)

Verdict: withhold. One blocking finding, on question one (a claim about the code that does not hold) with a question-two consequence. The goal reads on its own: a client value that the SQL binds to tenant 186 reaches tenant 186, and the consumer's own keys get the same equality. Every symbol, test and fixture name the spec cites exists as named, the capture and classification plan extends shapes already in the tree, and the representative-column-plus-agreement argument is sound. The blocker is the runtime primitive.

**1. `DataType.convert` does not fail on a value it cannot read, so the round trip as specified does not deliver the stated client-error contract on an unconverted column, and it truncates.** The spec says `canonicalTenantKey` is `converter.from(converter.to(type.convert(value)))` and that "a value it cannot read throws `IllegalArgumentException` naming the value", and it rests "an unreadable tenant value becomes a client error" for every multi-tenant build on that. Run against jOOQ 3.20.11, `SQLDataType.INTEGER.convert("abc")` returns `null` and `SQLDataType.INTEGER.convert("1.5")` returns `1`. For a column without a converter the identity converter passes both straight through. So on `TenantDivinedRoutingExecutionTest`'s `Integer` fixture, after this lands:

- `"abc"` canonicalises to `null` without throwing. In the agree-fold the outcome depends on where the fold tests for null, which the plan does not say: the "binding value is absent" `NoSuchElementException` (a server-side failure with the wrong message), a "disagree" client error naming `null`, or a leaf that silently drops out. In `fanOutDomain(env, narrowing)` it puts `null` into `keys`, `retainAll` empties the domain, and the field returns an empty list presented as complete: the defect class this item exists to remove. Today the same input fails loudly with `NumberFormatException`.
- `"1.5"` (and `"1e2"`, `" 186 "`) canonicalise to a different tenant, where today they are refused. That is the opposite of the spec's "a value the column cannot hold is a client error". The "Behaviour for an unconverted column" paragraph presents the coercion change as a benign widening (`Long` for `Integer`) and does not mention it.

The converter-backed fixture hides this: on `owner_org` the `DataType` is `DataType<String>`, so `convert` is a no-op and `OrgCodeStringConverter.to` throws `NumberFormatException` itself. The only unreadable-value test in the plan therefore cannot catch it. `DataType.convert` can also throw `DataTypeException`, which is not an `IllegalArgumentException`, for an input type it has no conversion for, and the `tenantKeyOf` catch as written would let that escape as a server error.

The leniency is not news to this tree. `ConnectionHelperClassGenerator`'s cursor decode already says so in a comment ("jOOQ's DataType.convert is lenient: an uncoercible wire token yields null rather than throwing"), then null-checks and catches `IllegalArgumentException | DataTypeException` into the client-error type. That is the shape the round trip should extend (question two) rather than restate wrongly.

What would satisfy it: the plan states what `canonicalTenantKey` does when `convert` yields `null` for a non-null input and when it throws `DataTypeException`, and where each becomes the client error. It also decides whether a lossy coercion such as `"1.5"` to `1` is accepted or refused on the tenant key, and how a refusal is detected, given that `"0186"` to `186` is not a value-preserving round trip either and must still be accepted. The Tests section gains identity-path cases on the `Integer` fixture for an unreadable value (`"abc"`) and for whatever lossy-coercion behaviour the plan picks, on both the routed path and the narrowed fan-out. The "Behaviour for an unconverted column" paragraph and the manual draft then say what a client of an unconverted build observes.
