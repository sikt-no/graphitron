---
id: R116
title: "Cover composite-key Row2 path-keyed @sourceRow classification"
status: Backlog
bucket: cleanup
priority: 8
theme: testing
depends-on: []
---

# Cover composite-key Row2 path-keyed @sourceRow classification

R110 shipped `@sourceRow` with `Row2..Row22` arity admitted by the resolver: the per-position type loop in `SourceRowDirectiveResolver` iterates the lifter's `RowN` type arguments without special-casing arity 1, and the leaf-PK arm constructs `LifterLeafKeyed` over whatever the leaf's `TableRef.primaryKeyColumns()` returns. The existing `SourceRowClassificationCase` test enum exercises Row2 only on the rejection path (`LEAF_PK_ARITY_MISMATCH` against `inventory.inventory_id`); no successful Row2 path-keyed classification fires anywhere in the test corpus today. The gap is in the test catalog (no 2-column FK exists in `graphitron-rewrite/graphitron/src/test/...`), not in resolver / emitter code.

This item adds (a) a 2-column FK to the test catalog (or selects an existing 2-column FK from Sakila when extending the sakila-example fixture), (b) a `COMPOSITE_KEY_ROW2_PATH_KEYED` case in `SourceRowClassificationCase` that asserts a `Row2<...>`-returning lifter classifies as `RecordTableField` with a `LifterPathKeyed` whose `parentSideColumns()` matches the FK's two-column source-side, and (c) a pipeline test exercising the parent VALUES emission and JOIN ON predicate render for the 2-column shape. Architect-review feedback from R110's In Review pass surfaced this as a real coverage gap that scopes outside R110 itself.
