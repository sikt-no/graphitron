---
id: R292
title: Descriptions on generated Connection/Edge boilerplate types
status: Spec
bucket: feature
priority: 5
depends-on: []
created: 2026-06-10
last-updated: 2026-06-10
---

# Descriptions on generated Connection/Edge boilerplate types

Graphitron-synthesised relay boilerplate (`<Query>Connection`, `<Query>Edge`, their `edges`/`nodes`/`pageInfo`/`totalCount`/`cursor`/`node` fields) carries no SDL descriptions, so consumers whose subgraph linting enforces Apollo's `ALL_ELEMENTS_REQUIRE_DESCRIPTION` get a violation per generated type and field (observed: 20+ violations on a real consumer schema, e.g. `QueryApplikasjonerConnection` and friends). The generator knows exactly what these types are for, so it can synthesise meaningful descriptions at the point where the Connection/Edge/PageInfo variants are built. Surfaced during R291 (which strips Graphitron-internal types from the published SDL); this is the complementary half of getting a lint-clean published schema, but it concerns client-facing generated types, not internal leakage.

## Design

The fix lives entirely in `ConnectionPromoter` (`no.sikt.graphitron.rewrite`), the single site that synthesises connection boilerplate. Its three private builders construct the programmatic `GraphQLObjectType` forms and carry them on the sealed variants `ConnectionType.schemaType()` / `EdgeType.schemaType()` / `PageInfoType.schemaType()`:

* `buildSynthesisedConnection` → type + `edges` / `nodes` / `pageInfo` / `totalCount`
* `buildSynthesisedEdge` → type + `cursor` / `node`
* `buildSynthesisedPageInfo` → type + `hasNextPage` / `hasPreviousPage` / `startCursor` / `endCursor`

None of them call `.description(...)` today; that is the entire gap.

**Single source of truth, parity for free.** Both published seams derive the description from the same `schemaType()` object:

* the emitted `schema.graphqls` is printed from the assembled `GraphQLSchema` by `SchemaSdlEmitter` through graphql-java's `SchemaPrinter` (descriptions on by default in `Options.defaultOptions()`);
* the runtime-rebuilt schema is generated Java: `ObjectTypeGenerator.buildObjectTypeSpec` emits `b.description(...)` whenever `objectType.getDescription()` is non-empty (line 137), and `buildFieldDefinitionMethod` does the same per field (line 243), reading straight off `ct.schemaType()`.

Writing the description once onto the synthesised `GraphQLObjectType` therefore lands on both seams with no second emission site to keep in sync. The live parity test `FederationBuildSmokeTest.emittedSdlMatchesRuntimeSchema` diffs the two via `SchemaDiffing`; since `SchemaDiffing` walks descriptions as part of the type-element graph, that test already guards the parity invariant for descriptions, and would fail the build on any divergence. It does *not*, however, pin description *presence* (it stays green if descriptions are absent on both sides) — hence the dedicated presence test below.

**Scope: the synthesised (directive-driven) path only.** The three `buildSynthesised*` methods run only when `@asConnection` sits on a bare list and the Connection name does not already exist in the SDL. The structural path (the consumer declared the `Connection` / `Edge` / `PageInfo` object types in SDL) references the assembled SDL type directly via `ctx.schema.getType(name)`; the consumer owns those descriptions there, and that SDL declaration *is* the override lever for anyone who wants different wording. We do not touch it. Likewise out of scope: the carrier field's own description (user-owned, lives on the `Query`/parent field that returns the connection).

**Wording.** Use the canonical graphql-relay-js descriptions, as private `static final String` constants inline on `ConnectionPromoter` (the single synthesis site; no separate holder, no second reader). Generic, not parameterised by element type: the generic wording is true for every synthesised type, whereas weaving in `elementTypeName` would make the generator responsible for phrasing that stays sensible across every element name and pluralisation, a claim it cannot guarantee on boilerplate. Mapping:

| Element | Description |
|---|---|
| `Connection` type | `A connection to a list of items.` |
| `edges` field | `A list of edges.` |
| `nodes` field | `A list of nodes.` |
| `pageInfo` field | `Information to aid in pagination.` |
| `totalCount` field | `Identifies the total count of items in the connection.` |
| `Edge` type | `An edge in a connection.` |
| `cursor` field | `A cursor for use in pagination.` |
| `node` field | `The item at the end of the edge.` |
| `PageInfo` type | `Information about pagination in a connection.` |
| `hasNextPage` field | `When paginating forwards, are there more items?` |
| `hasPreviousPage` field | `When paginating backwards, are there more items?` |
| `startCursor` field | `When paginating backwards, the cursor to continue.` |
| `endCursor` field | `When paginating forwards, the cursor to continue.` |

## Implementation

* `ConnectionPromoter.java`: declare the thirteen wording constants; add `.description(...)` to the type builder and each `GraphQLFieldDefinition.newFieldDefinition()` in `buildSynthesisedConnection`, `buildSynthesisedEdge`, and `buildSynthesisedPageInfo`. No signature changes, no new call sites, no touch to the structural path or `rebuildAssembledForConnections`.

## Tests

* `ConnectionPromoterTest` (unit): extend the existing `directiveDrivenBareList_emitsCarrierAndSynthesisesTypes` (or add a focused case) to assert that the synthesised `ConnectionType.schemaType()` / `EdgeType.schemaType()` / `PageInfoType.schemaType()` and each of their field definitions carry the expected non-empty `getDescription()`. Assert against the `GraphQLObjectType` model, not against any emitted Java method-body string.
* Pipeline tier (`SchemaSdlEmissionTest` in `graphitron-sakila-example`): add a presence assertion that the emitted `schema.graphqls` for the existing directive-driven `@asConnection` fixture carries the synthesised connection/edge/`PageInfo` descriptions, confirming they survive the `SchemaPrinter` seam end to end.
* `FederationBuildSmokeTest.emittedSdlMatchesRuntimeSchema` continues to pass unchanged: it pins that the descriptions land identically on both seams.

## Out of scope

* Structural (SDL-declared) Connection / Edge / PageInfo types: consumer-owned descriptions; the SDL declaration is the override.
* The carrier field's own description.
* Making the wordings configurable: the structural-SDL path already serves as the override; no directive/config surface is added.
