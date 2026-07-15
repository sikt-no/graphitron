---
id: R481
title: "Single-cardinality parent-holds-FK polymorphic child fields crash on non-key parent correlation column"
status: Backlog
bucket: architecture
priority: 3
theme: interface-union
depends-on: []
created: 2026-07-15
last-updated: 2026-07-15
---

# Single-cardinality parent-holds-FK polymorphic child fields crash on non-key parent correlation column

A single-cardinality multi-table interface/union child field whose **parent** table holds the foreign key to the participant crashes at runtime. `MultiTablePolymorphicEmitter`'s single-fetch form (`buildScalarPerParentFetcher` → `branchParentFkWhere`) correlates each participant against the parent's bound key by reading the parent-side column off the already-fetched `parentRecord` (`parentRecord.get(DSL.name("<col>"), …)`), and `ParticipantCorrelation.KeyTupleWhere` assumes that parent-side column is the parent's primary key. But when the parent holds the FK, the correlation's parent side is the FK column, not the PK, and the parent record projects only its key columns plus the GraphQL fields the client selected. So the emitted `parentRecord.get("<fk-col>")` names a column the row type never loaded and throws `IllegalArgumentException: Field "<fk-col>" is not contained in row type …`.

Discovered during the R458 slice-1 review (see that item's "Slice-1 review (2026-07-14)" note). It is **not** self-FK-specific: the self-referencing `category.parent_category_id` "navigate to parent" case is one instance, but the plain `Customer.address: Named` shape in the R281 classified corpus (parent `customer` holds `address_id` to the `address`-backed participant) is the same pattern. `Customer.address` only survives because the classified corpus asserts classification, never execution, so the latent crash is unexercised there.

A classification-time guard is the wrong fix: the emitter reads the column off a runtime record whose projection depends on the query's selection set, which the classifier cannot see, and a "parent-side must be the PK" guard over-rejects the legitimate `Customer.address` corpus case (it classifies and is expected to). The correct fix is to **project the parent-side correlation column onto the parent record** whenever a single-cardinality polymorphic child field correlates on a non-key parent column, so `parentRecord.get(...)` always finds it, mirroring how the non-polymorphic single self-reference (`Category.parent` via `@reference`) already materialises its correlation. Scope at pickup: confirm the batched list / connection forms (which correlate the child's FK against `parentInput`, parent side = bound key) are unaffected, and decide whether `Customer.address` gets an execution fixture as part of the fix so the shape is guarded going forward.
