---
id: R678
title: "Capture expands @asFacet the way it already expands @asConnection"
status: Backlog
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
---

# Capture expands @asFacet the way it already expands @asConnection

`MacroCapture` already runs macro expansion inside the capture walk: `expandConnections` mints the
Relay Connection and Edge declarations a `@asConnection` carrier implies, writing them through the
same doors an authored row goes through with `graphitron_type_declaration_synthesis` provenance
saying an expansion put them there. `@asFacet` has no such expansion. Capture records the marker
(`graphitron_facet`) and stops, so the facet types the walk mints exist only in the walk's rebuilt
assembled schema and never in the store.

That is the last hole in the store's picture of the effective schema, and it is load-bearing beyond
tidiness. `DemandShadowTest`'s reach agreement compares `intent_type_domain` against
`SchemaReachability.reachableTypeNames` as a plain equality, subtracting exactly one population: the
walk's own facet verdicts. Closing this expansion is what lets that subtraction go, and it is the
precondition for a classification walk that reads the store instead of a `GraphQLSchema`
(`roadmap/capture-precedes-the-classification-walk.md`): a builder with no schema cannot mint types
that only a schema rebuild produces.

The template is in the tree. `expandConnections` establishes the pattern (carriers collected during
the walk, minted after it so a type's own rows are never interleaved, provenance rows for the
anti-join that recovers the authored picture, and nothing that rejects), and the walk-side twin to
pin against is `ConnectionPromoter`'s facet arm. Per `MacroCapture`'s own doctrine the two stay
pinned to each other by the agreement suite rather than one calling the other.
