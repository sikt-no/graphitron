---
id: R292
title: "Descriptions on generated Connection/Edge boilerplate types"
status: Backlog
bucket: feature
priority: 5
depends-on: []
created: 2026-06-10
last-updated: 2026-06-10
---

# Descriptions on generated Connection/Edge boilerplate types

Graphitron-synthesised relay boilerplate (`<Query>Connection`, `<Query>Edge`, their `edges`/`nodes`/`pageInfo`/`totalCount`/`cursor`/`node` fields) carries no SDL descriptions, so consumers whose subgraph linting enforces Apollo's `ALL_ELEMENTS_REQUIRE_DESCRIPTION` get a violation per generated type and field (observed: 20+ violations on a real consumer schema, e.g. `QueryApplikasjonerConnection` and friends). The generator knows exactly what these types are for, so it can synthesise meaningful descriptions at the point where the Connection/Edge/PageInfo variants are built (the same place R10 notes the synthesised types get their graphql-java forms), e.g. "Pagination connection for <field>" / "The item at the end of the edge". Surfaced during R291 (which strips Graphitron-internal types from the published SDL); this is the complementary half of getting a lint-clean published schema, but it concerns client-facing generated types, not internal leakage. Descriptions must appear identically on the emitted `schema.graphqls` and the runtime-rebuilt schema, or the R253/R291 parity test trips.
