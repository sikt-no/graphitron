---
id: R290
title: "Field-side dimensional slots: materialise carrier x intent x mapping on the field"
status: Backlog
bucket: structural
priority: 4
theme: structural-refactor
depends-on: [dimensional-model-pivot, intention-classification-dimension]
created: 2026-06-10
last-updated: 2026-06-13
---

# Field-side dimensional slots: materialise carrier x intent x mapping on the field

This is R222's Stage 3 spin-out: the field-side half of the dimensional pivot. See R222's
**Field-side dimensional model (refined 2026-06-13)** for the target this slice implements. The summary:
a field's classification is `carrier x intent x mapping` plus a derived layer, and **the producer
dimension dissolves**. (An earlier draft of this item framed the field as carrying a "producer pipeline
over `{Query, Service, Dml}`, length <= 2, empty meaning inline-correlate"; that framing is retired,
there is no producer pipeline.)

Today a field's execution shape is encoded by its position in the fused
`QueryField` / `MutationField` / `ChildField` cross-product permit set, so `DataFetcherBuilder` reads the
leaf identity to decide how to fetch. This slice dissolves that cross-product: the field carries its
**carrier** (which *is* its type), **intent**, and **mapping** directly, and the fetcher/loader mechanism
is **derived** rather than switched on a leaf name:

- catalog vs domain (build-vs-consume) rides the `mapping` (`Table`/`Column` vs `Record`/`Field`); there
  is no separate provider to materialise.
- `FetchRelated` derives from the join-path slot.
- **re-fetch** derives from `(Service | DML intent) x Table mapping` — this is the old service/DML ->
  `@table` re-query (the earlier "`[Service, Query]`" pipeline), now a derived consequence of a
  domain/write producer yielding a catalog-table shape, not a chain the field stores.
- **new-query** (fold-vs-batch / split) derives from a `SourceField` slot forced by `@splitQuery` /
  polymorphic UNION / record-handoff.
- polarity (mutating?) derives from the intent family.

Concretely this collapses the service/DML re-query permits (`QueryServiceTableField`,
`MutationServiceTableField`, `ChildField.ServiceTableField`, and the record-carrier siblings) onto a
service/DML intent + `Table`/`Record` mapping with the re-fetch derived, and it **deletes R281's
throwaway `LeafTupleAdapter`** once the field exposes `(carrier, intent, mapping)` directly.

## Leaf changes carried by this slice

- **Rename `ChildField` -> `SourceField`** (carrier-named, per R222's refined model). `QueryField` /
  `MutationField` already match their carriers.
- **Dissolve `ConstructorField`** — dead since the `@record`-on-types ban; its only path is an edge case
  not in use. Delete the leaf, its `LeafTupleAdapter` arm, and any generator dispatch, after verifying no
  live reference.
- **Collapse `SingleRecordTableField`** — its verdict is the `(Service`/`DML) x Table` re-fetch; the
  single-source-object DataLoader-skip becomes a derived detail, not a distinct leaf.

## Acceptance

R281 (`classification-test-dsl`, shipped) plus R299 (`intention-classification-dimension`) are this
item's **executable acceptance spec**, and R290 depends on R299 so the corpus already speaks the new
model before the adapter is deleted. After R299 the corpus asserts `(carrier, intent, mapping)`, not leaf
names, not the retired `producer`. When this slice lands the adapter is deleted, the harness reads the
slots directly, and **every corpus assertion must stay byte-identical** — their continued green proves
the decomposition was behaviour-preserving. (The dissolution of `ConstructorField` and collapse of
`SingleRecordTableField` are the two corpus rows that legitimately change; everything else stays
byte-identical.)

The merge gate is both tiers green: the R281/R299 corpus for the decomposition, and the pipeline /
`TypeSpec` tier for the slot-level emit (a derived-slot change keeps the corpus green, so that stays the
pipeline tier's job). `QueryBuilder` and `ValidationBuilder` are sibling Stage 3 consumers the same
corpus drives; the Stage 1 foundation (`ServiceField` / `ServiceMethodCall`) has landed. See the R281
entry in `roadmap/changelog.md`.
