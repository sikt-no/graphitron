---
id: R290
title: "Field-side dimensional slots: materialise carrier x intent x mapping on the field"
status: Backlog
bucket: structural
priority: 4
theme: structural-refactor
depends-on: [dimensional-model-pivot]
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

The `ChildField` -> `SourceField` carrier rename is **split out to R302**
(`rename-childfield-to-sourcefield`): it is ~940 references of pure mechanical churn with no
behavioural change, and folding it into this slice's diff would bury the architectural change under
rename noise. R302 and this slice are independent (no ordering edge); whichever lands second rebases
trivially. The two leaf-set changes below stay here, because they change the corpus and are coupled to
the materialisation:

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

## Appendix: leaf inventory (the verdicts R290 materialises)

Every current `OutputField` leaf under the `carrier × intent × mapping` model, with the derived columns
shown. This is the worked target R290 implements; **totality holds, no leaf has an unfilled cell.**
`ConstructorField` (dissolved) and `SingleRecordTableField` (collapsed into the `(Service`/`DML) × Table`
re-fetch) are absent by design, the leaf set is 47. Derived legend: `FR` = `FetchRelated` (from a
non-empty join-path), `RF` = re-fetch (from a `(Service`|`DML`) × `Table` mismatch), `NQ` = new-query
(`SourceField` slot, forced by `@splitQuery` / polymorphic / record-handoff). The orthogonal slot column
(method, batch-key, join-path, bulk, composite, participants) carries per-leaf detail; the triple is the
classification, not the whole emit-determinant.

### Query carrier (`QueryField`)

| Leaf | intent | mapping | derived | slot |
|---|---|---|---|---|
| QueryTableField | Fetch | Table | | |
| QueryLookupTableField | Lookup | Table | | |
| QueryTableMethodTableField | Fetch | Table | | method |
| QueryTableInterfaceField | Fetch | Table[poly] | | participants |
| QueryInterfaceField | Fetch | Table[poly] | | participants |
| QueryUnionField | Fetch | Table[poly] | | participants |
| QueryNodeField | NodeResolve | Table[poly] | | |
| QueryNodesField | NodeResolve | Table[poly] | | (list) |
| QueryServiceTableField | QueryService | Table | RF | |
| QueryServiceRecordField | QueryService | Record | | |

### Mutation carrier (`MutationField`)

| Leaf | intent | mapping | derived | slot |
|---|---|---|---|---|
| MutationInsertTableField | Insert | Column / Table | RF (when Table) | |
| MutationUpsertTableField | Upsert | Column / Table | RF (when Table) | |
| MutationUpdateTableField | Update | Column / Table | RF (when Table) | |
| MutationDeleteTableField | Delete | Column | | encoded-id only (R287) |
| MutationServiceTableField | MutationService | Table | RF | |
| MutationServiceRecordField | MutationService | Record | | |
| MutationDmlRecordField | Insert/Update/Upsert | Record | | |
| MutationBulkDmlRecordField | Insert/Update/Upsert | Record | | bulk |
| MutationUpdatePayloadField | Update | Record | | |
| MutationBulkUpdatePayloadField | Update | Record | | bulk |
| MutationDeletePayloadField | Delete | Record | | |
| MutationBulkDeletePayloadField | Delete | Record | | bulk |

### Source carrier (`SourceField`; rename from `ChildField` is R302)

| Leaf | intent | mapping | derived | slot |
|---|---|---|---|---|
| ColumnField | Fetch | Column | | |
| ColumnReferenceField | Fetch | Column | FR | join-path |
| ParticipantColumnReferenceField | Fetch | Column[poly] | FR | join-path, participant |
| CompositeColumnField | Fetch | Column | | composite |
| CompositeColumnReferenceField | Fetch | Column | FR | composite, deferred-stub |
| TableField | Fetch | Table | FR | (inline) |
| LookupTableField | Lookup | Table | | (inline) |
| TableInterfaceField | Fetch | Table[poly] | FR | participants (R288) |
| TableMethodField | Fetch | Table | FR | method |
| SplitTableField | Fetch | Table | FR, NQ | batch-key |
| SplitLookupTableField | Lookup | Table | NQ | batch-key |
| RecordTableField | Fetch | Table | FR, NQ | record-key |
| RecordLookupTableField | Lookup | Table | NQ | record-key |
| RecordTableMethodField | Fetch | Table | FR, NQ | method, record-key |
| ServiceTableField | QueryService | Table | RF | |
| ServiceRecordField | QueryService | Record | | |
| RecordField | Fetch | Field | | |
| PropertyField | Fetch | Field | | |
| ComputedField (`@externalField`) | Fetch | Field / Record | | reflection (reclassified from `Column`) |
| NestingField | Nesting | Table | | (reclassified from `Fetch`) |
| InterfaceField | Fetch | Table[poly] | FR, NQ | participants |
| UnionField | Fetch | Table[poly] | FR, NQ | participants |
| SingleRecordIdFieldFromReturning | Fetch | Column | | reads RETURNING |
| SingleRecordIdField | Fetch | Column | | encode-from-record |
| ErrorsField | Fetch | Record | | reads Outcome |

### Connection protocol roles (Source carrier; not current leaves)

Behind the ConnectionType quarantine; classifying them is a separate item. Their intents (`Count`,
`Facet`) are among the declared model-completeness gaps.

| Role | intent | mapping | derived |
|---|---|---|---|
| edges | Fetch | Table | NQ |
| totalCount | Count | Column | |
| facets | Facet | Record | |
| nodes | Fetch | Table | |
| pageInfo | Fetch | Record | |

Two reclassifications this materialisation forces, called out because they change live leaves:
`ComputedField` (`@externalField`) moves catalog `Column` → domain `Field`/`Record` (provider/mapping
classifies epistemic role, not SQL location), and `NestingField` moves `Fetch` → `Nesting`. Five intents
are modeled-but-unpopulated by the current leaf set (declared gaps): `EntityResolve`, `Count`, `Facet`,
`UpdateMatching`, `DeleteMatching`.
