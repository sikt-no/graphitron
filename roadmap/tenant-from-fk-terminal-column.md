---
id: R992
title: "A reference whose FK lands on the tenant column routes on the parent row value"
status: In Progress
bucket: architecture
priority: 4
theme: runtime-connection
depends-on: []
created: 2026-10-07
last-updated: 2026-10-09
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
3. The parent's rows are read from the default source: the first hop's origin table (`ParentCorrelation.parentKeyOwnerTable`) is global. Today that is exactly "does not carry the tenant column" (`Fold.tenantScoped` answering false); the condition is stated as *where the rows live* because R505's declared index tables carry the column and still live on the default source (see Siblings).
4. One of the first hop's slots has the tenant column as its target side (`JoinSlot.FkSlot.targetSide`, matched as `Fold.matchesTenantColumn` matches). The paired `sourceSide` is the *tenant-carrying parent column*, and it is a key column by condition 2.
5. Every hop's target table is tenant-scoped, so the whole statement can run in one tenant database.
6. The field binds no tenant itself (`Fold.directBinding` divines nothing and declines nothing). An argument that maps to the tenant column keeps `ArgumentBound`, as today.

These settle the open points the Backlog stub left:

- **Column-pair hops only.** A condition hop (`On.Predicate`) or a hop-0 filter (which lands `OnParentJoin`, keyed on the parent's primary key and anchoring the global parent table inside the child's statement) names no column whose value equals the tenant by construction. Such fields keep today's verdict.
- **The tenant column must be on hop 0.** The batch key carries only hop 0's parent-side columns, and a tenant column that first appears on a later hop is paired with a column on an intermediate table, not on the parent row. That field keeps today's verdict. Later hops are fine as long as condition 5 holds, because they run inside the already-routed statement.
- **Condition 5 reads the whole path; the existing cross-scope check does not.** `Fold.reachedTables` sees only a field's return table, so the rung that refuses a statement touching both scopes is blind to join-path tables for every arm. Condition 5 keeps the new arm from routing such a statement, and the shared gap is R995's, which widens the one reach for every arm; this item does not change existing verdicts to close it.
- **The derived tenant wins over an inherited one, with no agreement guard.** The Backlog stub proposed refusing the field when the parent is also tenant-bound and the two disagree. Spec drops that. In a database-per-tenant deployment, tenant T's database holds rows whose tenant column is T. So an inherited tenant T can find the child row only when the parent row names T, which is exactly when the two agree. When they disagree, the inherited route finds nothing and the derived route finds the real row. A guard would refuse a legitimate read, such as a student at institution T whose place of study is institution X, and buy nothing. The arm also applies on the shape whether or not every path is bound, so the verdict is a function of the field's own correlation and stays stable when an unrelated `_entities` entry point is added.
- **This changes behaviour for schemas that compile today.** A field of this shape whose parent every path reaches under a binding classifies `Inherited` now and `ParentRowBound` after. Where the parent row names the inherited tenant nothing observable changes. Where it names another tenant X, the field returned `null` or an empty list and now returns X's row, or the not-permitted client error when X is outside the request's tenant set. The execution tests pin both.
- **Inline references are not routed per row.** An inline (non-split) child renders inside its parent's statement, which runs on the parent's connection, so it cannot reach a different database per row. When an inline `ChildField.TableField` meets conditions 2 to 6 and draws `NoTenantBinding` today, the rejection detail names the fix: mark it `@splitQuery`, and the parent row names the tenant.
- **Out of scope:** tenant-scoped parents (condition 3). A tenant-scoped parent under a binding keeps `Inherited`. A cross-tenant reference from a tenant-scoped row, whose parent-side column is not the parent's own tenant column, is known-wrong under `Inherited` by this item's own argument, and is filed as R996. Also out of scope: record-backed parents (`SourceShape.Record`), and the polymorphic, pivot and service batched variants. Each keeps its current verdict.

## Implementation

**Model: `TenantBinding.ParentRowBound`.** `record ParentRowBound(TableRef parentTable, JoinSlot.FkSlot slot)`: the global parent table and the first-hop slot whose target side is the tenant column. The emitter renders `slot.sourceSide()`. It gets its own paragraph in the `TenantBinding` interface javadoc, not a place in the per-row family's paragraph. That family is the "positional slot in a decoded key" shape partitioned at a dispatch surface, which `TenantDslEmitter` sends to the inherited read. `ParentRowBound` divines and hands down like `ArgumentBound` and partitions its loader like `Inherited`.

**Classification: `TenantBindingIndex.Fold`.**

- One structural predicate implements conditions 2 to 5 over a correlation and its join path (`ParentCorrelation`, `List<JoinStep>`), so it serves both the batched and the inline carriers. It returns the slot or nothing, and reads only the field's own model and the configured scopes, never ancestor context. That is what lets the ancestor fold call it before any arm is assigned.
- The arm is minted in exactly one place, by projecting off the field's `OnFkSlots` correlation: `parentKeyOwnerTable()` for the table and the matched `FkSlot` instance off `slots()`. The mint asserts the invariant the emitter depends on: the slot is one of `OnFkSlots.slots().slots()` by identity, and its `sourceSide()` is in the field's `sourceKey().columns()`. It fails with a generator-bug message otherwise, the same way `ParentCorrelation.checkCarrierInvariant` pins `firstHop`.
- `armOf`: after the `direct.divines()` arm and the node-dispatch arm, and ahead of both `Inherited` checks, a `BatchedTableField` (condition 1) the predicate matches returns `ParentRowBound`. It sits ahead of `Inherited` because of the precedence settled above.
- `edgeEstablishesContext` and `edgeDivinesTenant` both answer true for such an edge. The subtree below it is tenant-homogeneous per parent row, so its children classify `Inherited`, and a `@tenantFanOut` below it rejects as sitting under a tenant-bound ancestor, as it does below any binding.
- The generic `NoTenantBinding` detail on an inline `ChildField.TableField` the same predicate matches names `@splitQuery`. This is a detail string on the existing rejection, not a new rejection kind, and one predicate keeps the hint and the arm from drifting.

**Generated runtime: `ConnectionRuntimeClassGenerator`.** `tenantLoaderName` gains an overload taking the tenant explicitly: `tenantLoaderName(env, tenant)`. The existing one-argument form delegates to it with `env.getLocalContext()`, so the naming recipe keeps one home.

**Emission.** The fetch site extends the hand-down `ArgumentBound` already uses rather than adding a parallel one. `TenantDslEmitter.Resolution` declares `_divinedTenant` and carries `localContextTail()`, and the key extraction already reads every key column off the parent row:

- At the fetch site (`TypeFetcherGenerator.buildBatchedDataFetcher`, framed by `DataLoaderFetcherEmitter.build`), `ParentRowBound` resolves to a `Resolution` that declares `_divinedTenant` from the key extraction's per-column local for `slot.sourceSide()` (the `fkVal<i>` locals `GeneratorUtils.buildKeyExtractionWithNullCheck` declares), with `handsDownTenant` true. There is no second read of the parent row.
- `loaderNameDeclaration` partitions on that local: `tenantLoaderName(env, _divinedTenant)`. Each loader is then tenant-homogeneous, exactly as the `Inherited` partition is, giving one statement per tenant per batch.
- The batched fetch's async wrap tail takes the existing `localContextTail()`. `Inherited` children then read the tenant through `divinedTenant(env.getLocalContext())` as they do under an `ArgumentBound` root. One parent row means one tenant for the whole field value, so this is a single stamp, not per-element stamping like the fan-out arm's.
- A null tenant-carrying column means the FK points nowhere. The existing null-key short-circuit, which today `buildKeyExtractionWithNullCheck` emits only for the single-cardinality `Wrap.Row` key, extends to this arm at every cardinality the field can carry. The empty value it returns is read off the launcher row's `ResultShape` (`SingleRecord`, a record list, or a connection) rather than hand-listed per cardinality; no existing short-circuit produces an empty connection today, so that arm is new. No loader is created and no connection is taken.
- **Cost to plan for:** `DataLoaderFetcherEmitter.build` emits the loader-name declaration before its `try` and the key extraction inside it, so for this arm the extraction moves ahead of the name. Key extraction for the `Record` and `TableRecord` wraps (`GeneratorUtils.buildKeyExtraction`) must expose the same per-column locals for the arm to read. The `try` routes a throw out of key extraction through the field's error disposition (the comment in `build` says why), so the reorder moves the loader name and registry lookup into the guarded region after the extraction rather than hoisting the extraction out of it.
- At the rows method, `resolve` gives `ParentRowBound` `dslFor(dfe, <slot read>)`, where `dfe` is `batchEnv.getKeyContextsList().get(0)` (`RowsMethodCall.batchLoaderLambda`) and the read is the same per-column key read over `dfe.getSource()`. The partitioned loader name makes every key context in a batch agree on the tenant, the argument `Inherited` already rests on for `dfe.getLocalContext()`. Reading the tenant off the key itself is not available: `SourceKey.Wrap.Row` keys have no value accessors.
- Every other exhaustive switch over `TenantBinding` gets the arm, and the compiler names them all. At `dslExpression`, `handDownOnly` and the `TenantAcquisition` producer in `RoutineWriteCommands`, the arm is unreachable: only a `BatchedTableField` carries it. There it throws a generator-bug `IllegalStateException`, in the style of the existing `FanOut` arms.

**Authorization.** Nothing new. Every keyed acquisition funnels through the generated carrier's `entryFor`, which refuses a tenant outside the request tenant set before the hosting check (R975). A parent row naming a tenant outside the set fails that tenant's loader batch with the existing client error ("Tenant '…' is not permitted for this request."). Every element in that batch gets the path-bearing error, and other tenants' batches resolve. This is the documented behaviour for a tenant "handed down from a parent", not the `null` answer the `node`/`_entities` lookups give.

**Docs.** `docs/manual/how-to/tenant-scoping.adoc` §2 gains the paragraph drafted below, after the paragraph beginning "Routing is divined from the operation".

**Migration debt.** `TenantBindingIndex` is a walk-side fold, part of the transitional surface `docs/architecture/explanation/pipeline-overview.adoc` ("The strangler frame") says is drained rather than extended. Moving the tenancy axis into the store is R682's, and doing it here would make this item that one. The new arm therefore adds one arm to an axis R682 already moves, and adds no new walk-side registry. The predicate's catalog half, column-pair slots from a default-source table landing on the tenant column, is a pure catalog-plus-configuration derivation. It is the natural first relation when R682 moves tenancy to the store.

## User documentation (first-client check)

> A reference from a global table into tenant data can route on the parent row. When the foreign key a field follows lands on the tenant column, the parent row's value at the paired column names the tenant that holds the child row, so each parent row's child is fetched from that tenant's database. The field must be `@splitQuery`, because an inline child runs inside its parent's statement on the default source. Parents naming the same tenant share one batched statement. A parent with no value at that column has no child. Like any routed tenant, the value must be in the request's tenant set, or that child fails with a client error. The parent row decides even when the parent was reached under another tenant, because a tenant's database holds only rows carrying that tenant: a registry row pointing at institution X has its child in X's database, whichever tenant the request arrived through.

## Tests

- **Classification** (`TenantBindingClassificationTest`, over the `film_id` fixture configuration):
  - The sakila pair above: `FilmEndorsement.film` classifies `ParentRowBound` on `film_endorsement` with the `endorsed_film` slot, `Film.inventories` below it classifies `Inherited`, `Query.endorsements` is `Untenanted`, and the build has no rejections. The case also asserts the mint invariant against the classified field: the arm's slot is the field's first-hop slot and its source side is in the field's `sourceKey().columns()`.
  - The same field without `@splitQuery` draws `NoTenantBinding`, and the detail names `@splitQuery`.
  - Precedence: `Query.films(filmId:) → Film.endorsements: [FilmEndorsement!]! @splitQuery → FilmEndorsement.film @splitQuery`, where every path is bound, still classifies `ParentRowBound`, not `Inherited`.
  - A condition-joined reference from `film_endorsement` to `film` keeps `NoTenantBinding`.
  - A `@tenantFanOut` field below the `ParentRowBound` edge draws the tenant-bound-ancestor rejection.
- **Pipeline** (`TenantRoutedFetcherPipelineTest`), in the style of its existing `Inherited` batched case: the `ParentRowBound` fetcher declares the row-partitioned loader name, short-circuits a null tenant column at single and list cardinality, and hands the tenant down. Its rows method acquires through the slot read.
- **Execution** (`TenantDivinedRoutingExecutionTest`, real database-per-tenant): `multitenant.graphqls` gains `FilmEndorsement` and `Query.endorsements`. The default database holds endorsements of films 1 and 2.
  - `endorsements { film { title inventories { inventoryId } } }` returns each film from its own tenant database, with one acquisition per tenant on the counting data sources and the inventories inherited.
  - A request whose tenant set excludes tenant 2 resolves film 1, and fails film 2's element with the not-permitted error.
  - The behaviour change: an endorsement reached under tenant 1 whose film lives in tenant 2 returns tenant 2's film when tenant 2 is in the request set, and the not-permitted error when it is not. The fixture needs a path from a tenant-1-bound parent to an endorsement of film 2, for example a condition-joined `Film.endorsements` that returns every endorsement. The FK-joined one returns only the film's own.
  - This doubles as the compile proof that the emitted fetcher and rows method build at `<release>17</release>`.

## Implementation notes

Landed as specified, in one commit. Where the build departs from or settles something the plan left open:

- **The null short-circuit covers every key column, not only the tenant-carrying one.** `GeneratorUtils.buildKeyExtractionThroughLocals` reads each key column into its `fkVal<i>` local and returns the empty value when any is null. A null component of any key column can never match the correlation, so this answers what the loader would have, and the existing single-cardinality `buildKeyExtractionWithNullCheck` now delegates to the same body. Record and TableRecord wraps declare the same locals ahead of their own key read.
- **The empty connection carries no count source.** The connection arm of the short-circuit answers `new ConnectionResult(List.of(), <defaultPageSize>, null, null, false, List.of(), null, null, null)`, so `totalCount` and `facets` read `null`, as they do on any carrier the count query does not cover. On a schema that declares `totalCount: Int!`, a parent with a null tenant column therefore surfaces the non-null error rather than `0`; answering `0` would need either a connection on some source or a change to the generated count resolver, and neither is worth it for a parent row whose reference points nowhere.
- **The cross-tenant execution path goes through two global tables, not a condition-joined `Film.endorsements`.** The plan's suggested fixture joins from tenant-scoped `film` to global `film_endorsement` by condition, which lands `OnParentJoin` and anchors `film` inside a default-source statement: a cross-scope read, the kind R995 makes the build refuse. The fixture instead reaches the endorsements as `films(filmId: 1) { inventories { store { endorsements { film } } } }`: `Inventory.store` is an FK-joined, untenanted batched child, and `Store.endorsements` is a condition join between two global tables (`ReferencePathConditionFixtures.everyTenantFixtureEndorsement`, which selects the fixture's rows by note), so every statement stays in one scope.
- **`Query.endorsements` takes a `note` filter.** The default database is shared with concurrently running execution classes that insert their own endorsements of films 2 and 3; the filter keeps this class's reads to its own rows.

Coverage, by the plan's test list: `TenantBindingClassificationTest` (the five classification cases, under "A reference whose foreign key lands on the tenant column routes on the parent row"), `TenantRoutedFetcherPipelineTest` (`parentRowBound*`: single and list short-circuits, key-first framing, hand-down, rows-method slot read, inherited child), and `TenantDivinedRoutingExecutionTest` (`parentRowBound_*`, four cases against database-per-tenant PostgreSQL, compiled at `<release>17</release>` with the rest of `multitenant.graphqls`).

## Reviewer findings

### In Review → Ready (rework), reviewing `1707b1c` on trunk at `318d547`

The verification build (`mvn install -Plocal-db`) is green, and question 1 holds. The classification, the precedence ahead of `Inherited`, the single mint and its invariant, the key-first framing, the loader partition, the hand-down, the rows-method slot read and the unreachable arms all match the plan. The fixture substitution and the `note` filter are disclosed above, and they are sound. The manual paragraph contains no roadmap markers. No retired vocabulary is declared. What sends the item back is question 2, together with the test-tier precondition.

1. **The new pipeline tests are code-string assertions on generated method bodies.** `parentRowBoundFetcherPartitionsOnTheRowsTenantAndHandsItDown`, `parentRowBoundRowsMethodAcquiresThroughTheSlotRead` and `parentRowBoundChildInheritsTheHandedDownTenant` render `MethodSpec.toString()` and match emitted Java text, including two `indexOf` ordering checks. `development-principles.adoc` bans this at every tier, and this gate holds it as an approval precondition. The rest of `TenantRoutedFetcherPipelineTest` already follows that pattern, but that does not license new cases. The Tests section's "in the style of its existing `Inherited` batched case" steered the implementation here, so it is a plan defect as well as a delivery defect. Amend that bullet too. The behaviours these strings pin belong in the compile and execution tiers. Execution already covers what they show about partitioning, hand-down and the slot read. A pipeline case that stays should assert classification or `TypeSpec` shape, not body text.

2. **Two promised behaviours have no evidence other than those strings.**
   - *A null tenant-carrying column answers the empty value and takes no connection.* The plan states this, and the manual promises it to users ("A parent with no value at that column has no child"). No execution case exercises it, and the fixture cannot: `film_endorsement.endorsed_film` is `NOT NULL`. A nullable reference from a global table onto `film.film_id` is needed. That can be a nullable column or a small global table in `init.sql`, whichever disturbs the catalog least. It also needs an execution case asserting `null` (single) and `[]` (list), with no acquisition on either tenant's counting source.
   - *List cardinality, and the connection arm of the empty value.* The only `ParentRowBound` field `graphitron-sakila-example` compiles is the single-valued `FilmEndorsement.film`. So the list fetcher, the non-`Row` branch of `GeneratorUtils.buildKeyExtractionThroughLocals` that a list key takes, and the new empty-`ConnectionResult` arm never compile or run in any tier. (I checked by hand that the connection constructor's arity matches the multi-tenant 9-argument form.) Add a list-shaped `ParentRowBound` field to `multitenant.graphqls`; the pipeline fixture's two-key `FilmEndorsement.inventories` path fits. Add an execution case asserting each endorsement's inventories come from the tenant its row names. Also add a connection-shaped one, which at least gives the empty-connection arm its compile proof at `<release>17</release>`.

3. **Spec bookkeeping (non-blocking on its own, but due at the next pass).** "Landed as specified, in one commit" should name the landing SHA, and the Tests section should describe the evidence that ships after items 1 and 2.

What satisfies this: items 1 and 2 delivered, the Tests section amended to match, and a green verification build.

## Siblings

- **R505** (Backlog): the same per-row routing for a *declared* tenant-index table, one that carries the tenant column but whose rows live on the default source. This item lands the `ParentRowBound` arm, its slot read, and the loader partition. R505's classification half adds a third scope value: carries the column, lives on the default source. Condition 3 consumes that value. Making an index table fail the column test would not do, because slot matching (`Fold.matchesTenantColumn`) ignores scope, so an argument mapped to the index's tenant column would still classify `ArgumentBound` and route a default-source table to a tenant database. Reuse is narrower than "every index hop": the rule routes an index-to-child hop only where the index's tenant column is a hop-0 slot, which makes it a key column. A hop that pairs only on other columns, such as a student id, leaves the tenant off the key and needs its own read. R505 depends on this item.
- **R682** (Spec): dissolves the walk, `tenantBindings` included; the arm added here moves with the axis (see Migration debt).
- **R995** (Backlog): the cross-scope check sees every table on a batched statement's join path; the gap condition 5 works around for this arm only.
- **R996** (Backlog): the cross-tenant reference from a tenant-scoped parent, routed wrong under `Inherited` today; scoped out here.
- **R975** (Done): the request tenant set and its membership check, which the derived tenant passes through unchanged (see Authorization).
- **R986** (Done): the cycle fold judged by the edges entering a cycle. This item adds one edge shape that establishes context and leaves the fold itself alone.
- **R988** (Done): non-resolvable `@key` entries, which narrow the `_entities` entry points. `Organisasjon`'s key is resolvable, so it does not help here.
