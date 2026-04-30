---
id: R55
title: "Collapse EntityFetcherDispatch per-typeId VALUES emission onto LookupValuesJoinEmitter"
status: Backlog
bucket: architecture
theme: nodeid
depends-on: []
---

# Collapse EntityFetcherDispatch per-typeId VALUES emission onto LookupValuesJoinEmitter

`EntityFetcherDispatchClassGenerator` hand-rolls its own per-typeId `VALUES (idx, col1, ...)` derived-table-plus-JOIN emission via `select<TypeName>Alt<N>`, parallel to the VALUES + JOIN pipeline `LookupValuesJoinEmitter` already drives for `@lookupKey` lookups (`Query.foo(keys: [...])`, `[FilmActorKey!]! @lookupKey`, etc.). After R50, both pipelines emit the same SQL shape — `VALUES + JOIN + ORDER BY idx` — but live in two separate code paths. R50 pinned the dispatcher's emitted shape with a regression test (`GraphQLQueryTest.nodes_perTypeIdBatch_emitsValuesJoinOrderByIdxShape`) so the dispatcher can't silently regress to the legacy `WHERE row-IN`, but the underlying emission stays parallel. Per *Generation-thinking* ("the same multi-arm type switch recurs across multiple generators"), the two pipelines want to collapse: per-typeId batches re-point through `LookupValuesJoinEmitter.buildInputRowsMethod` + `buildFetcherBody` with a synthesized `LookupMapping.ColumnMapping` carrying one `ScalarLookupArg` (single-key target) or `DecodedRecord` (composite). The dispatcher's idx-driven cross-typeId scatter and `QueryNodeFetcher.rowsNodes`'s rep-synthesis-and-dispatch shape stay; only the per-typeId SQL emission changes hands. Spec needs to handle the dispatcher's tenant-scoped DSLContext plumbing (each rep gets a per-rep DFE so `getTenantId(repEnv)` resolves against the individual rep), the `__typename` synthetic column projection, and federated `_entities` parity. See the R50 (`lift-nodeid-out-of-model`) changelog entry for the originating context — this item was filed as the "Deferred" follow-on to R50's phase (f-E) regression test.
