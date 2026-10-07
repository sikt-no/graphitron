---
id: R992
title: "A reference whose FK lands on the tenant column routes on the parent row value"
status: Spec
bucket: architecture
priority: 4
theme: runtime-connection
depends-on: []
created: 2026-10-07
last-updated: 2026-10-07
---

# A reference whose FK lands on the tenant column routes on the parent row value

## Goal

Some fields start on a global parent, a table without the configured `<tenantColumn>`, and reach a tenant-scoped child, a table that has it. When such a field reaches the child through a foreign key whose child-side column *is* the tenant column, it takes its tenant from the parent row and no longer draws `NoTenantBinding`. The join itself says which tenant holds the child row: the parent's paired column carries the value the child's tenant column must equal. So the tenant is known per parent row even when the parent arrived through `_entities` or `node` with no tenant in scope, and the "global registry row to its per-tenant settings row" shape becomes servable.

Terms used below. A *tenant-scoped* table carries the configured tenant column and lives once per tenant database; a *global* table does not, and lives on the default source. A field's *tenant binding* is where its tenant comes from, decided at build time: an argument, a decoded id, an ancestor that handed one down (*inherited*), or nothing, which is the `NoTenantBinding` build error. The *every-path rule* lets a field inherit only when every path from a root into its parent type crosses a field that established a tenant.

The case from the sis Graphitron 10 port (`<tenantColumn>INSTITUSJONSNR_EIER</tenantColumn>`), the last error sis has outside R990 and one field on hold:

```graphql
type Organisasjon implements Node @table(name: "INSTITUSJON") @key(fields: "organisasjonskode") {
  id: ID! @shareable
  organisasjonskode: String! @field(name: "INSTITUSJONSNR") @shareable
}

extend type Organisasjon { eierInstitusjon: Eierinstitusjon @splitQuery }

type Eierinstitusjon implements Node @table @shareable {
  id: ID!
  schac: String @field(name: "SCHAC")
  pic: String @field(name: "PIC")
}
```

```
'Organisasjon.eierInstitusjon' reaches tenant-scoped table 'eierinstitusjon' with no tenant binding
in scope: no argument or input field maps to tenant column 'INSTITUSJONSNR_EIER', and no ancestor
established a tenant context. ...
```

The jOOQ key `INSTITUSJON__HAR__EIERINSTITTUSJON__FK` pairs `INSTITUSJON.INSTITUSJONSNR` with `EIERINSTITUSJON.INSTITUSJONSNR_EIER` (`EIERINSTITUSJON__PK`). `INSTITUSJON` is global; `EIERINSTITUSJON` carries the tenant column. `Organisasjon` is reached from tenant-bound parents (`Student.larested`, `Emne.larested`), but it is also an entity with a resolvable `@key` and a `Node`, so `_entities` and `node` enter it with no tenant, and the every-path rule rejects the child. After this item, `eierInstitusjon` reads each `Organisasjon` row's `INSTITUSJONSNR` and fetches that row's `Eierinstitusjon` from that institution's database, whichever way the `Organisasjon` arrived.

The same shape over the sakila fixture catalog, with `film_id` as the tenant column (the configuration the tenant tests already use): `film_endorsement` is global, and its FK `endorsed_film` lands on `film.film_id`.

```graphql
type FilmEndorsement @table(name: "film_endorsement") {
  note: String
  film: Film @splitQuery          # today: NoTenantBinding; after: routed on endorsed_film, per row
}
type Film @table(name: "film") {
  title: String
  inventories: [Inventory!]! @splitQuery   # inherits the film's tenant
}
type Query { endorsements: [FilmEndorsement!]! }   # global, default source
```

## The rule

A field binds its tenant from the parent row (the new `ParentRowBound` arm below) when all of these hold:

1. It is a `ChildField.BatchedTableField` (a `@splitQuery` child, or any other child that already gets its own DataLoader fetch) with `SourceShape.Table`, so the parent is a `@table` row and the child runs as its own statement on its own connection.
2. Its `parentCorrelation` is `ParentCorrelation.OnFkSlots`, so the first hop joins on column pairs (`On.ColumnPairs`, catalog FK or name-matched key alike; the slots are direction-blind), and the DataLoader key is the first hop's parent-side columns (`ParentCorrelation.parentKeyColumns`).
3. The first hop's origin table, the parent table, is global.
4. One of the first hop's slots has the tenant column as its target side (`JoinSlot.FkSlot.targetSide`, matched as `Fold.matchesTenantColumn` matches). The paired `sourceSide` is the *tenant-carrying parent column*.
5. Every hop's target table is tenant-scoped, so the whole statement can run in one tenant database.
6. The field binds no tenant itself (`Fold.directBinding` divines nothing and declines nothing). An argument that maps to the tenant column keeps `ArgumentBound`, as today.

These settle the open points the Backlog stub left:

- **Column-pair hops only.** A condition hop (`On.Predicate`) or a hop-0 filter (which lands `OnParentJoin`, keyed on the parent's primary key and anchoring the global parent table inside the child's statement) names no column whose value equals the tenant by construction. Such fields keep today's verdict.
- **The tenant column must be on hop 0.** The batch key carries only hop 0's parent-side columns, and a tenant column that first appears on a later hop is paired with a column on an intermediate table, not on the parent row. That field keeps today's verdict. Later hops are fine as long as condition 5 holds, because they run inside the already-routed statement.
- **The derived tenant wins over an inherited one, with no agreement guard.** The Backlog stub proposed refusing the field when the parent is also tenant-bound and the two disagree. Spec drops that. In a database-per-tenant deployment, tenant T's database holds rows whose tenant column is T. So an inherited tenant T can find the child row only when the parent row names T, which is exactly when the two agree. When they disagree, the inherited route finds nothing and the derived route finds the real row. A guard would refuse a legitimate read, such as a student at institution T whose place of study is institution X, and buy nothing. The arm also applies on the shape whether or not every path is bound. Otherwise adding an unrelated `_entities` entry point would silently change where an existing field reads from.
- **Inline references are not routed per row.** An inline (non-split) child renders inside its parent's statement, which runs on the parent's connection, so it cannot reach a different database per row. When an inline `ChildField.TableField` meets conditions 2 to 6 and draws `NoTenantBinding` today, the rejection detail names the fix: mark it `@splitQuery`, and the parent row names the tenant.
- **Out of scope:** tenant-scoped parents (condition 3). A tenant-scoped parent under a binding keeps `Inherited`. A cross-tenant reference from a tenant-scoped row, whose parent-side column is not the parent's own tenant column, is a separate question with no client yet. Also out of scope: record-backed parents (`SourceShape.Record`), and the polymorphic, pivot and service batched variants. Each keeps its current verdict.

## Implementation

**Model: `TenantBinding.ParentRowBound`.** A new arm of the per-row family beside `NodeIdBound` and `EntityRepBound`: `record ParentRowBound(TableRef parentTable, ColumnRef parentColumn)`, the tenant-carrying parent column and the global table it sits on. The interface javadoc's per-row paragraph widens to three members. Here the partition happens at the loader name, not at a dispatch surface. The arm is the one R505 names. See Siblings for how that item reuses it.

**Classification: `TenantBindingIndex.Fold`.**

- A structural predicate, `parentRowSlot(FieldCoordinates)`, implements conditions 1 to 5. It returns the slot or nothing, and reads only the classified field and the configured scopes, never ancestor context. That is what lets the ancestor fold call it before any arm is assigned.
- `armOf`: after the `direct.divines()` arm and the node-dispatch arm, and ahead of both `Inherited` checks, a field whose `parentRowSlot` is present returns `ParentRowBound`. It sits ahead of `Inherited` because of the precedence settled above.
- `edgeEstablishesContext` and `edgeDivinesTenant` both answer true for such an edge. The subtree below it is tenant-homogeneous per parent row, so its children classify `Inherited`, and a `@tenantFanOut` below it rejects as sitting under a tenant-bound ancestor, as it does below any binding.
- The generic `NoTenantBinding` detail on an inline `ChildField.TableField` that meets conditions 2 to 6 names `@splitQuery`. This is a detail string on the existing rejection, not a new rejection kind.

**Generated runtime: `ConnectionRuntimeClassGenerator`.** `tenantLoaderName` gains an overload taking the tenant explicitly: `tenantLoaderName(env, tenant)`. The existing one-argument form delegates to it with `env.getLocalContext()`, so the naming recipe keeps one home.

**Emission: `TenantDslEmitter` and `TypeFetcherGenerator.buildBatchedDataFetcher`.** One rendered expression reads the tenant off the parent row: the parent column projected off `env.getSource()`, through the same column read `KeyLift.FkColumns` already renders for the key. The column is a key column, so the parent's projection already carries it. Both sites use that expression over the `env` in scope:

- `loaderNameDeclaration`: `ParentRowBound` declares `tenantLoaderName(env, <row read>)`. Each loader is then tenant-homogeneous, exactly as the `Inherited` partition is, giving one statement per tenant per batch.
- `resolve` at the rows method: `ParentRowBound` acquires `dslFor(env, <row read>)` over the captured environment. A loader captures the environment of the fetch that created it, and the partitioned name guarantees that fetch's parent row names the loader's tenant.
- The fetch site short-circuits when the row read is null. A null FK points nowhere, so the field resolves to its empty value (`null`, an empty list, or an empty connection, whichever an unmatched key yields today) without creating a loader or taking a connection.
- The fetch site's result carries the row's tenant as `localContext`, so `Inherited` children read it through the existing `divinedTenant(env.getLocalContext())` path. One parent row means one tenant for the whole field value, so this is a single stamp on the field result, not per-element stamping like the fan-out arm's.
- Every other exhaustive switch over `TenantBinding` gets the arm, and the compiler names them all. At `dslExpression`, `handDownOnly` and the `TenantAcquisition` producer in `RoutineWriteCommands`, the arm is unreachable: only a `BatchedTableField` carries it. There it throws a generator-bug `IllegalStateException`, in the style of the existing `FanOut` arms.

**Authorization.** Nothing new. Every keyed acquisition funnels through the generated carrier's `entryFor`, which refuses a tenant outside the request tenant set before the hosting check (R975). A parent row naming a tenant outside the set fails that tenant's loader batch with the existing client error ("Tenant '…' is not permitted for this request."). Every element in that batch gets the path-bearing error, and other tenants' batches resolve. This is the documented behaviour for a tenant "handed down from a parent", not the `null` answer the `node`/`_entities` lookups give.

**Docs.** `docs/manual/how-to/tenant-scoping.adoc` §2 gains the paragraph drafted below, after the paragraph beginning "Routing is divined from the operation".

## User documentation (first-client check)

> A reference from a global table into tenant data can route on the parent row. When the foreign key a field follows lands on the tenant column, the parent row's value at the paired column names the tenant that holds the child row, so each parent row's child is fetched from that tenant's database. The field must be `@splitQuery`, because an inline child runs inside its parent's statement on the default source. Parents naming the same tenant share one batched statement. A parent with no value at that column has no child. Like any routed tenant, the value must be in the request's tenant set, or that child fails with a client error. The parent row decides even when the parent was reached under another tenant, because a tenant's database holds only rows carrying that tenant: a registry row pointing at institution X has its child in X's database, whichever tenant the request arrived through.

## Tests

- **Classification** (`TenantBindingClassificationTest`, over the `film_id` fixture configuration):
  - The sakila pair above: `FilmEndorsement.film` classifies `ParentRowBound(film_endorsement, endorsed_film)`, `Film.inventories` below it classifies `Inherited`, `Query.endorsements` is `Untenanted`, and the build has no rejections.
  - The same field without `@splitQuery` draws `NoTenantBinding`, and the detail names `@splitQuery`.
  - Precedence: `Query.films(filmId:) → Film.endorsements: [FilmEndorsement!]! @splitQuery → FilmEndorsement.film @splitQuery`, where every path is bound, still classifies `ParentRowBound`, not `Inherited`.
  - A condition-joined reference from `film_endorsement` to `film` keeps `NoTenantBinding`.
  - A `@tenantFanOut` field below the `ParentRowBound` edge draws the tenant-bound-ancestor rejection.
- **Pipeline** (`TenantRoutedFetcherPipelineTest`), in the style of its existing `Inherited` batched case: the `ParentRowBound` fetcher declares the row-partitioned loader name, short-circuits a null row read, and stamps the tenant on its result. Its rows method acquires through the row read.
- **Execution** (`TenantDivinedRoutingExecutionTest`, real database-per-tenant): `multitenant.graphqls` gains `FilmEndorsement` and `Query.endorsements`. The default database holds endorsements of films 1 and 2.
  - `endorsements { film { title inventories { inventoryId } } }` returns each film from its own tenant database, with one acquisition per tenant on the counting data sources and the inventories inherited.
  - A request whose tenant set excludes tenant 2 resolves film 1, and fails film 2's element with the not-permitted error.
  - This doubles as the compile proof that the emitted fetcher and rows method build at `<release>17</release>`.

## Siblings

- **R505** (Backlog): the same per-row routing for a *declared* tenant-index table, a table that carries the tenant column but is not partitioned. This item lands the `ParentRowBound` arm, the row read, and the loader partition. R505 then reduces to its classification half: a declared index table answers "global" to the scope test. Where its hop into tenant data pairs the index's tenant column with the child's, condition 3 holds and this rule routes it with no further machinery. R505 depends on this item.
- **R975** (Done): the request tenant set and its membership check, which the derived tenant passes through unchanged (see Authorization).
- **R986** (Done): the cycle fold judged by the edges entering a cycle. This item adds one edge shape that establishes context and leaves the fold itself alone.
- **R988** (Done): non-resolvable `@key` entries, which narrow the `_entities` entry points. `Organisasjon`'s key is resolvable, so it does not help here.
