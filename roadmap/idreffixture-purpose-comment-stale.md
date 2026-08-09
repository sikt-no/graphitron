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
block framed on the deleted `IdReferenceField` synthesis shim. The stale part is narrower than the
block reads: its stated purpose and its per-FK shim-versus-column-lookup prose have lost their
referent, while the sentence about `studieprogram` carrying `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS`
describes a mechanism that is untouched and load-bearing. A rewrite that took the whole header as
false would delete live facts along with the dead ones, so the three claims are graded separately
here.

- **Stale.** "Synthetic fixture for IdReferenceField synthesis shim tests" as the schema's stated
  purpose. The grammar item deleted the FK-qualifier synthesis arm along with
  `IdReferenceShimClassificationTest` and `IdReferenceShimWarnFormatTest`.
- **Stale.** The per-FK paragraphs reading behaviour off "the shim-before-column-lookup ordering",
  which decides "whether the field becomes IdReferenceField (shim wins) or ColumnField (column
  lookup wins)", and the closing "without the shim the field is Unresolved". The FK role shapes and
  the two qualifier strings those paragraphs derive are still exactly right, and still asserted in
  `JooqCatalogIdRefTest`; it is only the shim framing wrapped around them that has lost its
  referent.
- **True, keep it.** "`studieprogram` receives `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` via
  `NodeIdFixtureGenerator` so that `catalog.nodeIdMetadata("studieprogram")` returns present." The
  metadata mechanism is untouched and load-bearing: `JooqCatalog.nodeIdMetadata` reads the
  constants, `TypeBuilder.buildTableType` promotes `implements Node` + `@table` to a `NodeType` on
  their presence with no `@node` present at all, `NodeDeclaration.isNodeType` gates the inference
  path on them, and `NodeProvenance.Origin.METADATA` exists to record them as an identity source.
  `NodeIdFixtureGenerator` still maps `studieprogram`, and the generated `Studieprogram` still
  carries both constants. Only the trailing "satisfying the shim gate" purpose clause is wrong.

The DDL is live and worth keeping. Its consumers are now `JooqCatalogIdRefTest` (qualifier-map
derivation for the two FK role shapes) and `JooqRecordServiceParamPipelineTest` (`@reference(key:)`
FK selection over a table with two FKs to one target), and the fix is to restate the header on those
grounds. It matters because the comment is what the next person reads before deciding whether a
column or FK here is safe to change, and it currently points them at a mechanism that cannot break.

One question the rewrite has to settle rather than restate. With bare `@node` plus metadata,
`Studieprogram` resolves `typeId = "Studieprogram"` and `keyColumns = [STUDIEPROGRAM_ID]` off the
constants, but the no-metadata path defaults to the type name and the primary key, which are those
same two values. The metadata therefore routes the type down the metadata-provenance branch without
changing anything either current consumer asserts on. Either say that in the header explicitly (it
is there to exercise that branch), or decide the metadata has stopped earning its place on this
fixture; what the rewrite must not do is assert a dependency no test pins.

Found at the grammar item's In Review -> Done gate, in the tail of its retirement sweep; filed rather
than held because the sweep's ten anchored sites were all closed and this is prose in test-fixture
DDL. The original filing said "none of that exists any more", which over-reached across all three
claims at once; corrected on 2026-08-09 after the metadata mechanism was re-checked against main
sources. The neighbouring `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`
comment naming "the old findGraphQLTypeForTable detour" is a smaller instance of the same thing,
reading as history for a backstop whose underlying invariant still holds; fold it in or leave it,
but decide rather than miss it.
