---
id: R928
title: "Restore the v9 *ConnectionEdge name for synthesised edge types"
status: In Progress
bucket: bug
priority: 2
theme: legacy-migration
depends-on: []
created: 2026-09-07
last-updated: 2026-09-07
---

# Restore the v9 *ConnectionEdge name for synthesised edge types

## Goal

Restore the Graphitron 9 edge type name for connections synthesised by `@asConnection`, so a consumer's published schema keeps its `*ConnectionEdge` types across the upgrade. The rewrite generator renames every one of them relative to v9, and the rename is a breaking change to a published GraphQL contract: a client with an inline or named fragment on an edge type (`... on QueryLandConnectionEdge`) fails validation, Apollo schema checks report the old types as removed, and a subgraph with stability guarantees cannot adopt v10 without shipping a breaking release of its own even though nothing in its schema definition changed. The rename buys nothing functionally, so keeping the legacy name is both the cheapest fix and the one that needs no compatibility flag.

The sakila example schema carries the shape:

```graphql
type Query {
  stores: [Store!]! @asConnection @defaultOrder(primaryKey: true)
}
```

A directive-driven carrier (a bare list field that `@asConnection` rewrites into a connection) mints two types the author never wrote. v9 emitted them as `QueryStoresConnection` and `QueryStoresConnectionEdge`; the rewrite emits `QueryStoresConnection` and `QueryStoresEdge`. When this item lands the emitted SDL reads as v9 did:

```graphql
type QueryStoresConnection { edges: [QueryStoresConnectionEdge!]! nodes: [Store!]! pageInfo: PageInfo! totalCount: Int }
type QueryStoresConnectionEdge { cursor: String! node: Store! }
```

The `connectionName:` override follows the same rule, so `@asConnection(connectionName: "SharedMoviesConnection")` mints `SharedMoviesConnectionEdge`, which is also what v9 did.

## Current derivation

The formula is a substring replace on the connection type name, written inline at two call sites that must agree across the fact-store boundary (the fact store is the jOOQ-backed relational transcription of the schema that `graphitron-model` captures and `graphitron` reads):

* `ConnectionPromoter.promotionFor` (`graphitron`, the synthesis pass on the assembled graphql-java schema): `connName.replace("Connection", "Edge")`, in the directive-driven arm only.
* `MacroCapture.carriers` (`graphitron-model`, the capture-side expansion of the same directive into minted-type rows): the same replace against its private `CONNECTION_SUFFIX` / `EDGE_SUFFIX` constants, which nothing else reads.

The connection name both read comes from `ConnectionNaming.defaultConnectionName` (`<ParentType><FieldName>Connection`) or from the deprecated `@asConnection(connectionName:)` override. Downstream nothing re-derives the edge name: it reaches the emitted schema through one `registerSynthesised` call in the promoter and the `edgeName` component of `ConnectionSynthesisRelation.MintedShape`, and it reaches the generated Java through the type-named fetcher class (`GeneratedUnits.FETCHERS_SUFFIX` appends `Fetchers` to the type name), so `QueryStoresEdgeFetchers` is generated today.

The legacy formula is on record. v9's `MakeConnections.getConnectionTypeName` (module `graphitron-schema-transform` at tag `v9.3.0`) resolved the override or the `<ParentType><FieldName>Connection` default, and the edge type was minted as `connectionTypeName + SCHEMA_EDGE_SUFFIX` with the suffix `Edge`, override included. Appending `Edge` to the resolved connection name is therefore not a new convention but the restored one.

The promoter's structural arm is out of scope and stays untouched: it reads the author's declared `edges` element type verbatim, because a declared Connection's shape is author-owned.

## Implementation

* Give edge naming one formula beside `ConnectionNaming.defaultConnectionName` in `graphitron-model`: `ConnectionNaming.defaultEdgeName(String connectionName)` returning `connectionName + "Edge"`. It takes the resolved connection name, not the parent and field, so the override path and the default path share it without a second entry point. Its javadoc states the formula, names it as the Graphitron 9 contract, and says why it is append rather than substitute (the two hazards below). Update the class javadoc, which today describes only the Connection name.
* Say what the helper is, in its javadoc and here: a shared formula, not the shared fact. The fact store already holds the edge name as the `graphitron_minted_type` row and the `edges` field's `named_type` in `graphitron_minted_field`, both written by `MacroCapture`; the promoter recomputes it only because classification runs before capture in the pipeline. `defaultEdgeName` is the transitional shim that keeps the two evaluations equal until the promoter reads the minted row, and it is deleted with the promoter. Do not extend the class javadoc's "single source of truth" phrasing to it.
* The agreement between the two sides already has an enforcer and it needs no edit: `FactCaptureAgreementTest.synthesisProvenanceAgreesWithConnectionSynthesis` compares the schema walk's minted `EdgeType` names against the store's minted rows, so a one-sided change to either formula fails the build. Name it in the helper's javadoc so the next reader finds the gate rather than re-deriving it.
* `FacetNaming.facetsTypeName` stays in `graphitron` and is not folded into `ConnectionNaming`: the capture-side expansion mints no facet type, so `graphitron-model` has no reader for the facets name, and moving a formula into a module with no reader is the coupling this item removes, mirrored. It moves when capture starts minting facets.
* `ConnectionPromoter.promotionFor`, directive arm: replace the inline `replace` with `ConnectionNaming.defaultEdgeName(connName)`. The promoter already imports `ConnectionNaming` for the connection name, so this adds no dependency.
* `MacroCapture.carriers`: read `ConnectionNaming.defaultEdgeName(connectionName)` and delete the `CONNECTION_SUFFIX` and `EDGE_SUFFIX` constants, whose only use was this formula.
* Generated fetcher class names follow the type name and so move too: `QueryStoresEdgeFetchers` becomes `QueryStoresConnectionEdgeFetchers`. This is a consequence, not a compatibility concern: v9 named its generated classes after the same types, and the rewrite wires fetchers itself. The rendered corpus fragments under `docs/architecture/reference/_example-*.adoc` that name `*EdgeFetchers` are rewritten by `CorpusFragmentTest`, not by hand.
* Appending rather than substituting makes two override hazards unreachable, both read off the present formula. `String.replace` is a global substring replace, so an override `connectionName: "ConnectionsConnection"` yields `EdgesEdge` today and `ConnectionsConnectionEdge` after. An override carrying no `Connection` substring at all (`connectionName: "MovieFeed"`) leaves the edge name equal to the connection name today, so `registerSynthesised` registers a `ConnectionType` and an `EdgeType` under one name and `TypeRegistry.register` demotes the coordinate to `UnclassifiedType` with a "classified incompatibly" rejection that names neither the override nor the edge; after, the edge is `MovieFeedEdge` and the schema is valid. Both get a fixture so the property is pinned rather than incidental.
* What the append does not settle is the collision family those hazards belong to. `registerSynthesised` is a map put and the case-fold clash detector (`GraphitronSchemaBuilder.rejectCaseInsensitiveTypeCollisions`) groups registry entries by folded name, so an exact collision has group size 1 and is invisible to it: an override spelling another carrier's derived edge name (`connectionName: "QueryFooConnectionEdge"` beside `Query.foo: [Foo!]! @asConnection`), or spelling a declared type's name, survives whatever the formula. On the capture side the same shapes misroute rather than collide: `MacroCapture.mintType` yields on a claimed coordinate but the minted fields still land on whichever type owns the name. That is a diagnostics item of its own and is filed as `synthesised-type-exact-name-collision` (Backlog); this item restores a name and does not widen into it.
* The rename lands inside the 10.0.0 RC window. Anyone already on an RC sees synthesised edge types and their fetcher classes renamed once more, back to the v9 names; the `roadmap/changelog.md` entry at Done records that so an RC adopter can find it.

## Tests

Every expectation naming a synthesised edge type moves with the formula. The unit and pipeline tiers in `graphitron`:

* `ConnectionPromoterTest`: `QueryCustomersEdge` becomes `QueryCustomersConnectionEdge`; the override fixture's `MyCustomerEdge` becomes `MyCustomerConnectionEdge`. The `CustomerEdge` expectations are the structural arm reading a declared type and do not move.
* `GraphitronSchemaBuilderTest`: `QueryFilmsEdge`, `MyFilmsEdge`, `SharedEdge` become `QueryFilmsConnectionEdge`, `MyFilmsConnectionEdge`, `SharedConnectionEdge`. The case-insensitive clash row declares `type fooEdge` against `connectionName: "FooConnection"` and asserts a clash with the synthesised `FooEdge`; re-aim the declared type at `fooConnectionEdge` so the row keeps testing the clash rather than passing vacuously.
* `MacroCaptureTest`: `QueryFilmsEdge`, `ActorEdge` (override `ActorConnection`), `SharedEdge` (override `SharedConnection`) become `QueryFilmsConnectionEdge`, `ActorConnectionEdge`, `SharedConnectionEdge`, including the `graphitron_minted_conflict` rows keyed on `SharedEdge.node`.
* `ClassificationDomainTest`, `SchemaReachabilityTest`, `ConnectionFederationTagPipelineTest`: `QueryFilmsEdge` becomes `QueryFilmsConnectionEdge`.
* `ArchitectureDocSymbolGuardTest`: the `QueryFilmsEdge` and `CountryCitiesEdge` exemptions become `QueryFilmsConnectionEdge` and `CountryCitiesConnectionEdge`.
* Corpus and facts: `corpus/faceted-connection.graphqls` names `QueryFilmsEdge` in its classification assertion, and `facts/field-navigation.graphqls` asserts a `graphitron_field_navigation` row for `QueryFilmsEdge.node`; both move. `CorpusFragmentTest` then rewrites the rendered `_example-*.adoc` fragments.

The execution tier in `graphitron-sakila-example`:

* `SchemaSdlEmissionTest` asserts the descriptions on `QueryStoresEdge` in the emitted SDL; it becomes `QueryStoresConnectionEdge`, and that assertion is the one that checks the contract the issue is about where a consumer reads it. Extend it with a positive assertion that `QueryStoresEdge` is absent from the emitted registry, so a regression to the substitute formula fails here and not only at the unit tier.
* `SynthesisedConnectionRuntimeDescriptionTest` reads `QueryStoresEdge` off the runtime-rebuilt schema; it moves the same way.

New fixtures, one per override hazard, as `GraphitronSchemaBuilderTest` enum rows beside `EXPLICIT_CONNECTION_NAME` (the classification tier is where the minted names are cheapest to observe): `connectionName: "ConnectionsConnection"` classifies `ConnectionsConnectionEdge` as `EdgeType`, and `connectionName: "MovieFeed"` classifies `MovieFeed` as `ConnectionType` and `MovieFeedEdge` as `EdgeType` with no `UnclassifiedType` demotion. `MacroCaptureTest` gets the capture-side twin of the `MovieFeed` case, asserting the minted `edges` field type, so the two sides of the fact-store boundary are pinned to agree on the one case where the old formulas were least obviously equal.

Completeness for the goal is demonstrated by `SchemaSdlEmissionTest` asserting `QueryStoresConnectionEdge` and the absence of `QueryStoresEdge` in emitted SDL, plus the two hazard rows.

## Documentation

* `docs/manual/how-to/migrating-from-legacy.adoc` asserts of `@asConnection` that the rewrite "emits the same Relay connection contract", flagging only `connectionName:` as changed. That claim is false today and is why the reporter found no migration note; under this item it becomes true and stays as written. In the `connectionName:` section, state that the edge type is named `<connectionName>Edge`, as in legacy, so a reader with an override knows the contract holds for it too.
* `docs/manual/reference/directives/asConnection.adoc` says a `*Connection`, `*Edge`, and `PageInfo` are generated alongside the field without giving the names. State them at the level a schema author reads them: the field `Query.stores` gets `QueryStoresConnection` and `QueryStoresConnectionEdge`, an override replaces the first and the second follows it. `SchemaSdlEmissionTest` is the assertion that keeps that prose true.
* `docs/manual/how-to/connections.adoc` describes "a matching `Edge` type"; name it with the `Query.stores` example already on the page.
* `docs/architecture/reference/code-generation-triggers.adoc` names `CountryCitiesEdge` and `QueryFilmsEdge` in hand-written prose around the rendered fragments; both move. The comment above `Query.stores` in the sakila `schema.graphqls` names `QueryStoresEdgeType`; correct it.

## Roadmap entries

`synthesised-type-exact-name-collision` (Backlog, filed with this spec) takes the cross-carrier and synthesised-versus-declared exact collisions named above. It depends on nothing here; its fixtures assume the append formula, so it is best picked up after this item lands.

## Retired vocabulary

For the retirement sweep at Done: the type names `QueryStoresEdge`, `QueryFilmsEdge`, `CountryCitiesEdge`, `QueryPartiesEdge`, `QueryCustomersEdge` and the class names `QueryStoresEdgeFetchers`, `QueryFilmsEdgeFetchers`, `CountryCitiesEdgeFetchers`, `QueryPartiesEdgeFetchers`; the constants `CONNECTION_SUFFIX` and `EDGE_SUFFIX` in `MacroCapture`.

## Other solutions we've considered

The issue asks for one of three, in its own order of preference; this item takes the first.

1. Keep the v9 naming. Chosen. Two call sites and test churn, no new configuration surface, and it leaves every v9 consumer's published contract intact. It does rename types for anyone already on a v10 RC, which is the argument for doing it inside the RC window rather than after 10.0.0 goes stable.
2. A configuration flag for legacy edge naming, with new projects defaulting to the v10 names. Rejected as the primary fix. The formula sits on both sides of the fact-store boundary, the generate mojo exposes no naming configuration at all today, and a flag makes the emitted schema surface permanently dependent on plugin configuration in exchange for a name that buys nothing. That is the "Stability through simplicity" principle in `docs/graphitron-principles.adoc` applied to a naming choice: one name that never changes beats two names and a switch.
3. Document the rename as intentional and here to stay. Rejected as a fix, since it leaves consumers with stability guarantees no upgrade path at all. Its documentation half is worth doing either way and is folded into the documentation section above.

## Provenance

Reported while upgrading a subgraph in fs-plattform from 9.3.2 to 10.0.0-RC38: 13 synthesised edge types renamed, 3 of them in the published contract. See https://github.com/sikt-no/graphitron/issues/545.
