---
id: R928
title: "Restore the v9 *ConnectionEdge name for synthesised edge types"
status: Backlog
bucket: bug
priority: 2
theme: legacy-migration
depends-on: []
created: 2026-09-07
last-updated: 2026-09-07
---

# Restore the v9 *ConnectionEdge name for synthesised edge types

## Goal

Restore the v9 edge type name for `@asConnection`-synthesised connections, so a carrier at `Query.land` mints `QueryLandConnectionEdge` again instead of v10's `QueryLandEdge`. As it stands v10 renames every synthesised edge type relative to v9, which is a breaking change to a published GraphQL contract: a client with an inline or named fragment on an edge type fails validation, and Apollo schema checks report the old types as removed, so a subgraph with stability guarantees cannot adopt v10 without shipping a breaking release of its own even though nothing in its schema definition changed. The rename buys nothing functionally, so keeping the legacy name is both the cheapest fix and the one that needs no compatibility flag.

## Current derivation

The formula is a substring replace on the connection type name, written inline at two call sites that must agree across the fact-store boundary:

* `ConnectionPromoter.promotionFor` (`graphitron`, schema-transform side): `connName.replace("Connection", "Edge")`, in the directive-driven arm only.
* `MacroCapture` (`graphitron-model`, capture-side macro expansion): the same replace against its `CONNECTION_SUFFIX` / `EDGE_SUFFIX` constants.

The connection name those read comes from `ConnectionNaming.defaultConnectionName` (`<ParentType><FieldName>Connection`), or from the deprecated `@asConnection(connectionName:)` override. The promoter's structural arm is out of scope and stays untouched: it reads the author's declared `edges` element type verbatim, because a declared Connection's shape is author-owned.

Downstream nothing re-derives the name. It reaches the emitted schema through one `registerSynthesised` call plus the `edgeName` field on `ConnectionType`, so the blast radius is the two formulas and the expectations that name an edge type.

## Implementation sketch

* Give edge naming a single source of truth beside `ConnectionNaming.defaultConnectionName`: a `defaultEdgeName` that appends `Edge` to the connection name, read by both call sites, so the formula stops being duplicated across two modules. `FacetNaming.facetsTypeName` is the shape to copy, being the same append-a-suffix-to-the-connection-name derivation.
* Appending rather than substituting also clears two hazards in the present formula, both read off the code and not yet reproduced. `String.replace` is a global substring replace, not a suffix strip, so a `connectionName: "ConnectionsConnection"` override rewrites both occurrences. An override carrying no `Connection` substring at all (say `MovieFeed`) leaves the edge name equal to the connection name, registering two different synthesised types under one name; the carrier-conflict diagnostic in `ConnectionPromoter` covers two carriers minting one connection name with disagreeing shapes, not a connection/edge collision inside a single carrier. Reproduce both before deciding whether the append alone settles them.
* Correct `docs/manual/how-to/migrating-from-legacy.adoc`, which asserts of `@asConnection` that the rewrite "emits the same Relay connection contract", flagging only `connectionName:` as changed. That claim is false today and is why the reporter found no migration note; under this item it becomes true.
* State the derived edge name in `docs/manual/reference/directives/asConnection.adoc`, which says a `*Edge` is generated alongside the field without giving the formula.

## Tests

Every expectation naming a synthesised edge type moves with the formula; `ConnectionPromoterTest` asserts `QueryCustomersEdge` today. Completeness for the stated goal wants an emitted-SDL assertion pinning the legacy name for a directive-driven carrier, so the contract the issue is about is checked where a consumer would read it, plus a fixture per override hazard above.

## Other solutions we've considered

The issue asks for one of three, in its own order of preference; this item takes the first.

1. Keep the v9 naming. Chosen. Two call sites and test churn, no new configuration surface, and it leaves every v9 consumer's published contract intact. It does rename types for anyone already on a v10 RC, which is the argument for doing it inside the RC window rather than after 10.0.0 goes stable.
2. A configuration flag for legacy edge naming, with new projects defaulting to the v10 names. Rejected as the primary fix. The formula sits on both sides of the fact-store boundary, the generate mojo exposes no naming configuration at all today, and a flag makes the emitted schema surface permanently dependent on plugin configuration in exchange for a name that buys nothing.
3. Document the rename as intentional and here to stay. Rejected as a fix, since it leaves consumers with stability guarantees no upgrade path at all. Its documentation half is worth doing either way and is folded into the implementation above.

## Provenance

Reported while upgrading a subgraph in fs-plattform from 9.3.2 to 10.0.0-RC38: 13 synthesised edge types renamed, 3 of them in the published contract. See https://github.com/sikt-no/graphitron/issues/545.
