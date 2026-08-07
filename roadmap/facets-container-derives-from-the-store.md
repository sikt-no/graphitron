---
id: R606
title: "The facets container derives from the store rather than minting at capture"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-07
last-updated: 2026-08-07
---

# The facets container derives from the store rather than minting at capture

`@asFacet` is the one expansion the capture walk cannot run, and the fact store currently has no
other home for it, so the `<Conn>Facets` and `<Scalar>FacetValue` shapes exist only in the
assembled-schema synthesis. Capture expands a macro when its contribution is a function of one
carrier's own declaration, which is what keeps a single file the unit of an incremental refresh.
The facets container fails that test: its shape reads through the carrier's arguments into the
filter input type's fields, for the `@asFacet` marker, the `@field(name:)` binding, and the value's
scalar and nullability. That input type is free to live in another file, so minting the container
during the walk would leave it stale whenever a facet is added to a file the carrier's own refresh
never re-reads. It is an aggregate over the whole schema, not a local expansion.

Every input the shape needs is already a captured column, which is what makes this a derivation
rather than a gap in capture: `graphitron_facet` marks the input fields, `graphitron_field_binding`
gives each one's bound column, `graphql_argument` links a carrier to its filter type, and
`graphql_field` carries the value's scalar and nullability. The container therefore computes as a
query over captured columns, which is exactly the boundary the fact-base's decode rule draws when it
keeps name resolution and effective-value defaulting out of the walk.

Open questions for the Spec pass. Where the derived types live, given that the base relations hold
declarations and a derived type is not a declaration anyone wrote. Whether the `facets` field on a
Connection is derived alongside the container or minted with the rest of the Connection's fields and
left dangling until the derivation runs. Whether the first-occurrence-wins dedup on a duplicate facet
name across a carrier's filter inputs stays where `ConnectionPromoter.facetSpecsFor` has it, or
becomes a detection now that the store can express the duplicate. And what the derived stratum's
provenance looks like, since the macro domains on the capture-side provenance relations deliberately
carry no FACET value.
