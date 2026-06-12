---
id: R290
title: "Field-side dimensional slots: producer pipeline in DataFetcherBuilder"
status: Backlog
bucket: structural
priority: 4
theme: structural-refactor
depends-on: [dimensional-model-pivot, intention-classification-dimension]
created: 2026-06-10
last-updated: 2026-06-10
---

# Field-side dimensional slots: producer pipeline in DataFetcherBuilder

This is R222's Stage 3 spin-out: the field-side half of the dimensional pivot. Today a field's
execution shape is encoded by its position in the fused `QueryField` / `MutationField` / `ChildField`
cross-product permit set (R222 line 27), so the `DataFetcherBuilder` reads the leaf identity to decide
how to fetch. The pivot dissolves that cross-product into dimensional slots: the field carries its
**producer** pipeline (the row-source / re-query chain, length &le; 2 over `{Query, Service, Dml}`,
empty meaning inline-correlate) directly, and the fetcher/loader mechanism (fold-vs-batch,
keyed-vs-correlated, the `SourceKey` and dispatch slots) is **derived** from that pipeline plus the
field's schema position rather than switched on a leaf name. The load-bearing case is the
service&rarr;`@table` re-query (`[Service, Query]`): a row-source terminates the SQL context and a
trailing `Query` re-enters the catalog to project the table. Concretely this collapses
`QueryServiceTableField` / `MutationServiceTableField` / `MutationServiceRecordField` /
`ChildField.ServiceTableField` / `ChildField.ServiceRecordField` (and the wider permit set) into slots
keyed by emit-relevant identity, deleting R281's throwaway `LeafTupleAdapter` once the field exposes
the same `(producer, mapping)` it currently reconstructs.

R281 (`classification-test-dsl`, shipped) is this item's **executable acceptance spec**: its corpus
asserts the two-axis `(producer, mapping)` verdict, not leaf names, so when this slice lands the adapter
is deleted, the harness reads the slots directly, and **every corpus assertion must stay byte-identical**
&mdash; their continued green proves the decomposition was behaviour-preserving. The dependency edge ran
R281 &rarr; this item (driver to consumer). The merge gate is therefore both tiers green: the R281
corpus for the decomposition, the pipeline / `TypeSpec` tier for the slot-level emit (a `SourceKey`-arm
change keeps the corpus green, so that stays the pipeline tier's job). The `QueryBuilder` and
`ValidationBuilder` consumers are sibling Stage 3 slices the same corpus drives; the Stage 1 foundation
(`ServiceField` / `ServiceMethodCall`) has already landed. R281 closed its value sets (slice 1) and its
full successful-coordinate corpus coverage (slice 3), so the corpus is available as this item's primary
acceptance tier. See the R281 entry in `roadmap/changelog.md`.
