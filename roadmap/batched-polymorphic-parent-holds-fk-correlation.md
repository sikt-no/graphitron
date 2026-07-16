---
id: R487
title: "Batched multi-table polymorphic child fields: support parent-holds-FK correlation"
status: Backlog
bucket: architecture
priority: 3
theme: interface-union
depends-on: []
created: 2026-07-16
last-updated: 2026-07-16
---

# Batched multi-table polymorphic child fields: support parent-holds-FK correlation

The batched (list and connection) forms of `MultiTablePolymorphicEmitter` correlate each participant branch by joining the participant's table to a `parentInput` VALUES table: `batchedBranchCorrelationChain` / `parentInputSlotPredicate` (renamed from `batchedBranchJoinPredicate` by R458 slices 2-3) emit `<participant>.<slot.targetSide()>.eq(parentInput.field("<slot.sourceSide().sqlName()>", ...))`, and `buildParentInputValuesEmitter` aliases `parentInput`'s columns to the parent's bound-key column names (`parentSourceKey.columns()`, the parent/hub primary key). This works only while every participant's hop-0 parent side is on the bound key: a `KeyTupleWhere` slot, and equally a `JoinedCorrelation` FK hop-0 slot, since both feed the same `parentInputSlotPredicate` lookup (a `JoinedCorrelation` condition hop-0 correlates on the bound key via `parentInputKeyPredicate` and is unaffected). That holds for the child-holds-FK orientation. A participant whose FK lives on the **parent** table (`customer.address_id` pointing at an `address`-backed participant), whether as the single hop or heading a longer route, has its parent-side slot on that FK column instead: `parentInput.field("address_id")` finds no such column and returns null, so the generated code is broken at runtime. Nothing about a cross-table FK's direction is constrained by field cardinality (the `selfRefFkOnSource = !isList` hint orients only same-table FKs), so a list or connection field with a parent-holds-FK participant classifies today and emits this broken form.

R481 closes the hole at build time with a cardinality-gated DEFERRED rejection in `FieldBuilder.classifyParticipantRoute` keyed to this item (see that spec's gap C). This item is the capability the rejection points at: making the batched forms carry the participants' parent-side correlation columns, not just the bound key, through the DataLoader key and into `parentInput`. That means the per-parent key tuple (`GeneratorUtils.buildRecordParentKeyExtraction` on table-backed parents, the accessor arms on record-backed ones) must project the union of correlation columns, `parentInput` must alias them, and key identity/dedup semantics need a decision (two parents sharing FK values currently share nothing, because the bound key is unique per parent; correlation columns are not). Scope at pickup: decide whether the key becomes (bound key + correlation columns) or per-participant keys, and whether the single-cardinality parent-holds-FK projection mechanism R481 ships can be reused for the extraction side.
