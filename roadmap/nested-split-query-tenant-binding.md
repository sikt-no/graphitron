---
id: R1009
title: "A @splitQuery field on a nesting type routes on its tenant binding"
status: Spec
bucket: architecture
priority: 6
theme: runtime-connection
depends-on: []
created: 2026-10-09
last-updated: 2026-10-12
---

# A @splitQuery field on a nesting type routes on its tenant binding

## Goal

In a database-per-tenant build (one with `<tenantColumn>` configured, so every tenant's rows live in their own database), a field on a nesting type gets the same tenant routing it would get if it sat directly on the enclosing `@table` parent. A nesting type is an object type without `@table`, reached through a field on a `@table` parent, so it shares that parent's row. Today a `@splitQuery` field on such a type picks no tenant at all: it reads through the fixed default data source and batches its DataLoader by query path alone, so one batch can mix tenants. `graphitron:validate` reports nothing. The sis Graphitron 10 port has 15 such fields in 10 wrapper types, all over tenant tables (for example `EmneFagkoblinger.fag` under `Emne @table(name: "EMNE")`), and they block its go-live. When this item lands, those fields read from the tenant their parent row came from, each DataLoader batch holds one tenant's keys, and a nested field that cannot be routed is a build error with the same message a flat field gets. A missing routing decision at any generated database access becomes a generator failure, not a silent read on the default source.

The minimal pair, on the multi-tenant sakila fixture (`graphitron-sakila-example/src/main/resources/graphql/multitenant.graphqls`, tenant column `film_id`). `Film.inventories` exists there today; `Film.stock` and `FilmStock` are the fixture addition this item makes:

```graphql
type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
    id: ID! @nodeId
    # Routed today: reads from the tenant the Film row was fetched under.
    inventories: [Inventory!]! @splitQuery @reference(path: [{key: "inventory_film_id_fkey"}])
    # A nesting edge: FilmStock has no @table and shares the Film row.
    stock: FilmStock
}

type FilmStock {
    # Today: default source, path-keyed loader. After: routed exactly like Film.inventories.
    inventories: [Inventory!]! @splitQuery @reference(path: [{key: "inventory_film_id_fkey"}])
}
```

## Mechanism today

Terms used below. The *tenant binding* (`TenantBinding`) is the per-field decision of where the tenant comes from, computed once after classification by `TenantBindingIndex` (the *fold*). Its arms that matter here are `Inherited` (an ancestor already fixed the tenant, so the field reuses it) and `ParentRowBound` (a global parent row names the tenant of the child row it references, through a foreign key that lands on the tenant column). `TenantDslEmitter` turns the arm into the generated `DSLContext` acquisition (`TenantConnections.dslFor(...)` for routed arms) and into the DataLoader name (`tenantLoaderName(env)` for routed arms, so each batch holds one tenant).

There are two independent causes, and either one alone produces the wrong fetcher:

1. **The fold never sees the field.** `TenantBindingIndex.Fold.run()` assigns arms to the coordinates in `schema.fields()`. `FieldBuilder` classifies a nesting type's fields inside the enclosing parent's classification (`classifyChildFieldOnTableType`, with `parentTypeName` set to the nesting type), and they live only in `ChildField.NestingField.nestedFields()`. So the coordinate gets neither an arm nor the `noTenantBinding` rejection, which is why validation stays green. `OperationMemberRelation` deliberately mints no rows for nested coordinates either (its class javadoc states that domain boundary); nested instances keep the leaf-local `OperationMembers.membersOf(OutputField)`.
2. **The nested Fetchers class is generated without the schema.** In `TypeFetcherGenerator.generate`, the branch for a nesting-reached row (`reach.wiringFor(row.typeName())`) calls `generateTypeSpec` with a null `GraphitronSchema`, and every `TenantDslEmitter` entry point returns the single-tenant form when the schema is null, before it looks up a binding.

Underneath both, `TenantDslEmitter` also falls back to the single-tenant `getDslContext(env)` form whenever `tenantBindingOf` returns null under `TenantScopes.Configured`, in `resolve`, `resolveByName`, `dslExpression`, `handDownOnly` and `loaderNameDeclaration`. That fallback is what let both causes stay silent.

The pieces the fix reuses already handle nesting types. The fold's ancestor-context graph (`buildEdges`, `foldAncestorContexts`) is built from the SDL, so a nesting type already gets a correct `tenantContextOf` answer: the nesting edge establishes nothing and passes on its parent's context. `ColumnBindingLedger` already holds rows for nested coordinates, because `FieldBuilder` records them under the nested `parentTypeName`. And a nested fetcher's `env.getSource()` is the enclosing parent's record (the nesting fetcher passes the source through), which is what `TenantDslEmitter.parentRowRead` reads.

## Implementation

**`TenantBindingIndex.Fold`: visit nested instances.**

- The constructor builds a nested-site index: a recursive descent through every `NestingField` in `fields` (including nesting inside nesting), keyed by the child's coordinate (nesting type name, child name). Each entry lists one site per classified instance, holding the instance (`OutputField`) and its members, read through the leaf-local `OperationMembers.membersOf`. A nesting type reached from several `@table` parents is classified once per parent, so one coordinate can have several sites.
- `armOf`, `parentRowBinding` and `directBinding` stop looking the field and its members up by coordinate (`fields.get(coord)`, `operationMembers.membersOf(coord)`) and take the site instead. The flat path builds its site from `fields` and the minted relation exactly as today; the nested path builds it from the index. Behaviour on flat coordinates is unchanged. The `fields.get` calls in `edgeEstablishesContext`, `edgeDivinesTenant` and `collectFromServiceCall` go through the same site lookup, so a nested `@splitQuery` edge that divines or is `ParentRowBound` establishes context for its own subtree, exactly as a flat one does. Where a nested edge has several sites, it establishes context only when every site does, which is the conservative reading.
- `run()` assigns arms to nested coordinates after the flat loop. Every site of a coordinate is run through `armOf`. If every site yields an arm and all are equal, that arm is recorded. If any site is rejected, the coordinate is rejected once, not once per site. If the sites disagree, the coordinate is rejected with a new message naming the nesting type and the parents whose arms differ. This matters because one `<NestingType>Fetchers` class is emitted per nesting type from one representative wiring (`NestingReach`), so it can carry only one acquisition per field. When the coordinate also has a flat arm (a nesting type that is also a record-backed result, the mixed-source reach `MixedSourceReachIndex` covers), the nested arm must equal the flat arm, under the same rejection.
- Marker ladders stay off nested sites. A nested site carrying `@tenantFanOut` or `@globalData` gets no arm and records no marker verdict, so `sweepUnreachedMarkers` keeps rejecting it with its current text, which already names nesting-backed parents as unsupported. Running the fan-out ladder there would mint a `FanOut` arm that the nested Fetchers class cannot emit: its launcher relation comes from `LauncherCommands.produceWithoutSchema`, which is always `TenantStrategy.Single`.
- Nested fields that do not batch get verdicts too, the same ones they would get flat. A nested inline `@reference` into tenant data under a tenant-scoped parent is `Inherited`. Under a global parent it is rejected with the existing `@splitQuery` hint, because the inline child would run inside the global parent's statement. That nested hole is closed by the same change.

**`ColumnBindingLedger.requireCovers`.** It also walks the nested instances' leaf-local condition members, so the coverage invariant holds for every slot the fold now reads.

**`TypeFetcherGenerator.generate`.** The nesting-reached branch passes `schema` to `generateTypeSpec`. The other readers of that parameter don't change anything here: `dualShapeBinding` is gated on a non-null `resultType`, which this branch never passes, and `buildTableInterfaceReprojection` serves the joined-table interface fetcher, which a nesting type never emits. In a single-tenant build every `TenantDslEmitter` entry point still returns the single-tenant form, because the scopes are not `Configured`, so single-tenant output stays byte-identical.

**`TenantDslEmitter`: no silent fallback under configured scopes.**

- In `resolve`, `dslExpression`, `handDownOnly` and `loaderNameDeclaration`, a null binding with a non-null schema, `TenantScopes.Configured` and a non-null `parentTypeName` throws a generator-bug `IllegalStateException` naming the coordinate. This is sound because the fold assigns every `OutputField` it visits an arm or a rejection, and `generate()` fails the build on validator errors before any emission, so a null binding at emission means the fold missed a coordinate. The schema-null fallback stays, for unit-tier callers that build the model only.
- `resolveByName` resolves through `tenantBindingOf` instead of `schema.fields()`, which never holds nested coordinates. Otherwise a nested multi-table polymorphic child would still fall back to the single-tenant form. `resolve` reads only the field's name off its carrier, so the binding is the whole input it needs.
- If the multi-tenant fixture or the corpus trips the new invariant at a site the fold does not visit, the fix is to widen the fold to that site, or to give the site an explicit arm. Never restore the silent fallback.

**User manual.** Add one sentence to `docs/manual/how-to/tenant-scoping.adoc`: fields of a type without `@table`, reached through a field on a `@table` type, route exactly as they would on that `@table` type, including the parent-row routing described in its section 2.

## Tests

- **Classification** (`TenantBindingClassificationTest`): a nesting type's `@splitQuery` field under a tenant-scoped parent classifies `Inherited`. Variants: one whose `@reference` carries a `@condition` join, one declared in an `extend type`, and one two nesting levels deep. Under a global parent, a nested `@splitQuery` child whose foreign key lands on the tenant column classifies `ParentRowBound`, and a nested child with no such key is rejected with `noTenantBinding`. A nesting type shared by a tenant-scoped parent and a global parent, whose child arms disagree, is rejected once with the new disagreement message. A nested child carrying `@tenantFanOut` keeps its current unreached-marker rejection.
- **Pipeline** (`TenantRoutedFetcherPipelineTest`): the generated `FilmStockFetchers` (or its equivalent in the test schema) reads `TenantConnections.dslFor(env, TenantConnections.divinedTenant(env.<Object>getLocalContext()))` and declares its loader name with `TenantConnections.tenantLoaderName(env)`. The nested `ParentRowBound` case declares the key-first framing with `tenantLoaderName(env, _divinedTenant)`. A unit test pins the new `TenantDslEmitter` invariant: a configured schema with a coordinate missing from the index throws, naming the coordinate.
- **Execution** (`TenantDivinedRoutingExecutionTest`, sakila-example): add `Film.stock: FilmStock` with `FilmStock.inventories` to `multitenant.graphqls`, and mirror the existing `Film.inventories` assertions for `stock { inventories }`: a query that divines a tenant through `films(filmId:)` reads the nested inventories from that tenant's database, and a request divining two different tenants never shares one loader batch between them. This is the acceptance evidence for the goal.

## Other solutions we've considered

- **Register nesting types' fields in `schema.fields()`.** That would make every per-coordinate consumer see them for free. It was rejected because the flat index, and `OperationMemberRelation` with it, deliberately leaves nested coordinates out: the same nested coordinate can be reached through more than one parent, with different members per reach, and a coordinate-keyed row would collapse those. The fold owns the one decision this item needs, so the agreement check lives there.
- **Fix only the null schema in `TypeFetcherGenerator`.** That alone emits nothing new, because the fold still holds no arm for the coordinate and the emitter falls back silently. Both causes have to go, and removing the fallback is what keeps the next coordinate the fold misses from slipping through the same way.

## Relation to other items

R557 (the `@splitQuery` marker completeness sweep) covers the marker on the nesting field itself, where it is inert. This item covers fields *of* a nesting type, where `@splitQuery` is consumed and produces a batched leaf. The two sets of coordinates are disjoint, and neither item depends on the other.

## Provenance

Reported by the sis Graphitron 10 port on 2026-10-09 against trunk 2653fcab4. Affected sis coordinates: `EmneFagkoblinger.fag`, `Sensurinformasjon.kommisjoner`, `KarakterResultat.regel`, `OppnaddKarakterEmnesamling.regel`, `EmneVurderingsperiode.forsteTermin`/`sisteTermin`, `EmneUndervisningsperiode.forsteTermin`/`sisteTermin`, `EmneUndervisningsoversikt.undervisningsterminer`/`undervisningStartterminIPeriode`/`undervisningStartterminIperiode`/`beskrivelseAvUndervisningsterminer`, `StudieoppbygningsdelVeivalg.veivalgperiodeForKull`, `StudieoppbygningsdelFrieEmnevalg.begrensninger`, `EmneVektingsinformasjon.vektingsreduksjonsregler`. They fail closed in sis today, since its default source refuses every connection, which blocks go-live but not the rest of the port.
