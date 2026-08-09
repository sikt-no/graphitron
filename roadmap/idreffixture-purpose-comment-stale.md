---
id: R615
title: "init.sql documents the live idreffixture DDL as serving deleted shim tests"
status: Backlog
bucket: tech-debt
priority: 2
theme: nodeid
depends-on: []
created: 2026-08-09
last-updated: 2026-08-09
---

# init.sql documents the live idreffixture DDL as serving deleted shim tests

`graphitron-sakila-db/src/main/resources/init.sql` opens the `idreffixture` schema with a header
block declaring it a "Synthetic fixture for IdReferenceField synthesis shim tests", explaining that
`studieprogram` receives node metadata "satisfying the shim gate", and describing per-FK behaviour in
terms of "the shim-before-column-lookup ordering" deciding "whether the field becomes
IdReferenceField (shim wins) or ColumnField (column lookup wins)". None of that exists any more: the
grammar item deleted the FK-qualifier synthesis arm along with
`IdReferenceShimClassificationTest` and `IdReferenceShimWarnFormatTest`. The DDL itself is still
live and worth keeping, but its actual consumers are now `JooqCatalogIdRefTest` (qualifier-map
derivation for the two FK role shapes) and `JooqRecordServiceParamPipelineTest`
(`@reference(key:)` FK selection over a table with two FKs to one target). The fix is to restate the
header on those grounds. It matters because the comment is what the next person reads before
deciding whether a column or FK here is safe to change, and it currently points them at a mechanism
that cannot break.

Found at the grammar item's In Review -> Done gate, in the tail of its retirement sweep; filed rather
than held because the sweep's ten anchored sites were all closed and this is prose in test-fixture
DDL. The neighbouring `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls` comment
naming "the old findGraphQLTypeForTable detour" is a smaller instance of the same thing, reading as
history for a backstop whose underlying invariant still holds; fold it in or leave it, but decide
rather than miss it.
