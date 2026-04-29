---
title: "`PageInfo` wiring design decision"
status: Backlog
bucket: cleanup
priority: 9
theme: pagination
depends-on: []
---

# `PageInfo` wiring design decision

`WiringClassGenerator` currently emits no `PageInfoWiring` class; `PageInfo` fields (`hasNextPage`, `hasPreviousPage`, `startCursor`, `endCursor`) resolve via graphql-java's default property fetcher against whatever object `ConnectionHelper.pageInfo()` returns. This works by convention today.

Decision: document "PageInfo always uses default property fetching" explicitly in the generator, or emit an explicit `PageInfoWiring` that pins the property names. Choose before a schema adds complex `PageInfo` fields.
