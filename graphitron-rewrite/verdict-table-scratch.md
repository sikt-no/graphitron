# Verdict table v2 (scratch) — carrier × intent × mapping, producer dissolved

NOT a spec. Re-table of every current `OutputField` leaf after the producer dimension collapsed
into carrier + derived slots. Compare against v1 (the `{QueryPart, Query, ReturnedValue}` cut) to
see what the collapse bought.

## Model under test

- **carrier** (GraphQL parent-type category, the field type): `Query` (root) | `Mutation` (root) |
  `Source` (every other non-Subscription type). Carrier is *position*, and it gates legality.
- **intent** (asserted):
  - read: `Fetch`, `Lookup`, `NodeResolve`, `EntityResolve`, `Count`, `Facet`, `Nesting`
  - write: `Insert`, `Upsert`, `Update`, `UpdateMatching`, `Delete`, `DeleteMatching`
  - service: `QueryService`, `MutationService`
- **mapping**: `Table` / `Column` (catalog, graphitron builds) | `Record` / `Field` (domain, graphitron
  consumes). Mapping *is* the build-vs-consume axis; there is no separate provider.
- **derived** (shown in the table, never asserted):
  - `FR` = `FetchRelated`, derived from a non-empty **join-path** slot
  - `RF` = **re-fetch**, derived from a (`Service`|`DML` intent) × `Table` mapping mismatch
  - `NQ` = **new-query**, a `SourceField` slot forced by `@splitQuery` / polymorphic / record-handoff
  - polarity (mutating?) derived from the intent family; not shown
- orthogonal capability slots (method-backed, batch-key, join-path, bulk, composite, participants)
  carry per-leaf detail; the triple is the classification, not the whole emit-determinant.

## Query carrier

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
| QueryServiceTableField | QueryService | Table | **RF** | |
| QueryServiceRecordField | QueryService | Record | | |

## Mutation carrier

| Leaf | intent | mapping | derived | slot |
|---|---|---|---|---|
| MutationInsertTableField | Insert | Column / Table | **RF** (when Table) | |
| MutationUpsertTableField | Upsert | Column / Table | **RF** (when Table) | |
| MutationUpdateTableField | Update | Column / Table | **RF** (when Table) | |
| MutationDeleteTableField | Delete | Column | | encoded-id only (R287) |
| MutationServiceTableField | MutationService | Table | **RF** | |
| MutationServiceRecordField | MutationService | Record | | |
| MutationDmlRecordField | Insert/Update/Upsert | Record | | |
| MutationBulkDmlRecordField | Insert/Update/Upsert | Record | | bulk |
| MutationUpdatePayloadField | Update | Record | | |
| MutationBulkUpdatePayloadField | Update | Record | | bulk |
| MutationDeletePayloadField | Delete | Record | | |
| MutationBulkDeletePayloadField | Delete | Record | | bulk |

## Source carrier (all ChildField leaves)

| Leaf | intent | mapping | derived | slot |
|---|---|---|---|---|
| ColumnField | Fetch | Column | | |
| ColumnReferenceField | Fetch | Column | **FR** | join-path |
| ParticipantColumnReferenceField | Fetch | Column[poly] | **FR** | join-path, participant |
| CompositeColumnField | Fetch | Column | | composite |
| CompositeColumnReferenceField | Fetch | Column | **FR** | composite, deferred-stub |
| TableField | Fetch | Table | **FR** | (inline) |
| LookupTableField | Lookup | Table | | (inline) |
| TableInterfaceField | Fetch | Table[poly] | **FR** | participants (R288) |
| TableMethodField | Fetch | Table | **FR** | method |
| SplitTableField | Fetch | Table | **FR**, **NQ** | batch-key |
| SplitLookupTableField | Lookup | Table | **NQ** | batch-key |
| RecordTableField | Fetch | Table | **FR**, **NQ** | record-key |
| RecordLookupTableField | Lookup | Table | **NQ** | record-key |
| RecordTableMethodField | Fetch | Table | **FR**, **NQ** | method, record-key |
| ServiceTableField | QueryService | Table | **RF** | |
| SingleRecordTableField | Fetch | Table | **NQ** | (re-projection continuation) |
| ServiceRecordField | QueryService | Record | | |
| RecordField | Fetch | Field | | |
| PropertyField | Fetch | Field | | |
| ComputedField (ExternalField) | Fetch | Field / Record | | reflection (was Column!) |
| NestingField | **Nesting** | Table | | (was Fetch — collision fixed) |
| ConstructorField | Fetch | Record | | assemble-from-parent (Nesting?) |
| InterfaceField | Fetch | Table[poly] | **FR**, **NQ** | participants |
| UnionField | Fetch | Table[poly] | **FR**, **NQ** | participants |
| SingleRecordIdFieldFromReturning | Fetch | Column | | reads RETURNING |
| SingleRecordIdField | Fetch | Column | | encode-from-record |
| ErrorsField | Fetch | Record | | reads Outcome |

## Connection protocol roles (Source carrier; not current leaves)

| Role | intent | mapping | derived |
|---|---|---|---|
| edges | Fetch | Table | NQ |
| totalCount | Count | Column | |
| facets | Facet | Record | |
| nodes | Fetch | Table | |
| pageInfo | Fetch | Record | |

---

## Mechanical tests

**1. Totality — PASSES. No `⚠` anywhere.** This is the headline. The two v1 holes are closed:
- *Write provider hole (A)* — gone. Writes are `Mutation` carrier + a write intent + a mapping; the
  producer that had nowhere to put them no longer exists. The carrier *is* the write gate.
- *Pure-read intent hole (B)* — gone. `RecordField`, `PropertyField`, `ErrorsField`, the
  `SingleRecordId*` leaves, `nodes`/`pageInfo` are all `Fetch` + a domain mapping (`Field`/`Record`).
  `Fetch` is the plain read *relative to the source the mapping names* (consume a field), not a
  query-only intent.

Every leaf carries `(carrier, intent, mapping)`. The model closes over the leaf set.

**2. Distinguishing — same as v1 (triple is not a unique key, by design).** Same-triple leaves
separate on orthogonal slots:
- `QueryTableField` vs `QueryTableMethodTableField` → method slot.
- `SplitTableField` vs `RecordTableField` → batch-key vs record-key slot (both `Fetch`/`Table`/`FR`/`NQ`).
- `ColumnField` vs `ColumnReferenceField` → join-path (which derives `FR`).
The derived columns (`FR` from join-path) now do some of this separating that v1 needed slots for. The
`NestingField`/`TableField` collision that flagged in v1 is **fixed**: `Nesting` vs `Fetch`.

**3. Every asserted intent used — five modeled-but-unpopulated gaps.** Exercised: `Fetch`, `Lookup`,
`NodeResolve`, `Nesting`, `Insert`, `Upsert`, `Update`, `Delete`, `QueryService`, `MutationService`.
No current leaf: **`EntityResolve`** (Federation `_entities`), **`Count`**, **`Facet`**,
**`UpdateMatching`**, **`DeleteMatching`**. These are declared gaps (model leads classifier), same
status as before — the model carries them, no leaf populates them yet.

**4. Carrier gates intent; intent×mapping derives re-fetch — holds.**
- write intents only on `Mutation`; `NodeResolve`/`EntityResolve` only on `Query`; `Nesting` only on
  `Source`. The carrier is the gate the producer used to be.
- `RF` fires exactly on (`Service`|`DML`) × `Table`, and nowhere else; `Service`/`DML` × `Record`/`Column`
  consume the returned value directly (no re-fetch). Clean.

---

## What changed from v1, and where it still wobbles

**Closed:** both `⚠` holes (A write provider, B pure reads). The composite (old D) dissolved into
`QueryService`/`MutationService` + `Table` mapping with `RF` derived — no two-part shape, polarity
preserved as the intent.

**Reclassifications forced by the new model:**
- `ComputedField` (`@externalField`): `QueryPart`/`Column` (catalog) → `Fetch`/`Field`|`Record` (domain,
  reflection-typed). Biggest single change; rests on "provider classifies epistemic role, not SQL
  location."
- `NestingField`: `Fetch`/`Table` → `Nesting`/`Table`.
- producer column deleted entirely; carrier + `NQ` slot replace it.

**Still wobbling (3 spots):**
1. **`ConstructorField`** — `Fetch`/`Record` or `Nesting`/`Record`? It assembles a `@record` from the
   parent record's fields, structurally a regroup (Nesting-like) but it does construct a new object
   rather than pure passthrough. Domain-side `Nesting`, or a plain domain `Fetch`?
2. **`SingleRecordTableField`** — it's the explicit re-projection continuation, but the re-fetch is now
   *derived* from (`Service`/`DML`) × `Table`. So is this leaf redundant with the `RF` derivation, i.e.
   does it survive the dimensional collapse or fold into `ServiceTableField`'s `RF`?
3. **`UpdateMatching`/`DeleteMatching` vs the `Bulk` payload leaves** — the `Bulk` variants source
   per-row WHERE columns (bulk-by-identity), so I mapped them to `Update`/`Delete` + bulk slot, leaving
   `UpdateMatching`/`DeleteMatching` (condition-matched) unpopulated. Confirm the `Bulk` leaves are
   identity-bulk, not condition-matched, or these gaps are wrong.

Net: the model lands — totality holds, both structural holes closed, carrier-gates-intent works, and
the only unexercised intents are the declared model-completeness gaps.
