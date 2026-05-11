---
id: R136
title: "Execution-tier coverage for FK-target/NodeType-keyColumns permutation"
status: Backlog
bucket: validation
priority: 4
theme: nodeid
depends-on: []
---

# Execution-tier coverage for FK-target/NodeType-keyColumns permutation

R131's permutation relaxation is pinned at the pipeline tier (`InputFieldFkTargetNodeIdCase.FK_TARGET_REORDERED_KEY_PERMUTATION_DIRECT_FK{,_SINGULAR}` in `NodeIdPipelineTest`), which asserts `liftedSourceColumns` is permuted into `@node.keyColumns` order on the resolver's `DirectFk` carrier. The end-to-end SQL correctness — that the emitted `BodyParam.RowEq` against `liftedSourceColumns` actually matches the right rows when joined against decoded NodeId values — is not exercised by an execution-tier test in this repo.

The motivating regression (a downstream `opptak-subgraph` schema with `Regelverksamling.@node(keyColumns: ["regelverksamling_kode", "organisasjonskode"])` and a FK declared as `(organisasjonskode, regelverksamling_kode)`) lives outside this tree; the pipeline-tier test proves the carrier shape is correct, but only an execution-tier round-trip proves the emitted SQL returns the rows it should.

The gap to close: add a query field in `graphitron-sakila-example` that consumes the `reordered_pk_parent`/`reordered_fk_child` fixture, plus a `GraphQLQueryTest` round-trip seeding a few rows and asserting the `@nodeId`-filtered query returns the expected `reordered_fk_child` rows (and zero rows for permuted-but-wrong-table inputs). Confirms the permutation logic produces semantically correct SQL, not just carrier-shape-correct classification.

Acceptance:

- `schema.graphqls` adds a `ReorderedPkParent` `@table @node` type and a query field like `reorderedChildByParent(filter: ReorderedChildFilter): [ReorderedFkChild!]!` consuming `input ReorderedChildFilter @table(name: "reordered_fk_child") { parentRef: ID! @nodeId(typeName: "ReorderedPkParent") }`.
- `init.sql` or a per-test fixture seeds at least one matching row pair and one non-matching row pair on `reordered_pk_parent` and `reordered_fk_child`.
- `GraphQLQueryTest` round-trip: encode a `ReorderedPkParent` NodeId, pass it as `parentRef`, assert the right `reordered_fk_child` row(s) come back.
- Empty/malformed-id cases optional but cheap.

Out of scope:

- Multi-hop permutation (R135).
- Other permutation cases on the argument side; R131's pipeline pin already covers the argument-side classifier path.
