# Verdict table (scratch) — provider / intent / mapping over the current OutputField leaves

NOT a spec. A throwaway test of the candidate vocabulary against every current `OutputField`
leaf, to see what closes and what wobbles. Leaf set taken from `LeafTupleAdapter` (exhaustive
switch) + the `DmlTableField` sealed-on-kind expansion.

Candidate vocabulary under test:

- **provider** = how the value reaches the resolver: `QueryPart` (a jOOQ part spliced into the
  enclosing statement) | `Query` (its own statement) | `ReturnedValue` (read a value handed back).
  Write side currently has **no provider** — that is finding A.
- **intent** = the operation: `Fetch` `Lookup` `Count` `Facet` `LookupResult` (reads) |
  `Insert` `Update` `Delete` `Upsert` (writes) | `QueryService` `MutationService` (service polarity).
- **mapping** = value shape: `Table` `Column` `Record` `Field` (poly rendered `Table[poly]`).
- Orthogonal capability slots (not axes): method-backed, batch-key/source, join-path, bulk,
  inherits-parent-context. These already exist as marker interfaces; the triple was never meant to
  be a unique key, but the collisions below show where they're load-bearing.

`⚠` marks a cell the candidate vocabulary cannot fill.

## Root query

| Leaf | provider | intent | mapping | note |
|---|---|---|---|---|
| QueryTableField | Query | Fetch | Table | |
| QueryLookupTableField | Query | Lookup | Table | |
| QueryTableMethodTableField | Query | Fetch | Table | method-backed |
| QueryTableInterfaceField | Query | Fetch | Table[poly] | |
| QueryInterfaceField | Query | Fetch | Table[poly] | |
| QueryUnionField | Query | Fetch | Table[poly] | |
| QueryNodeField | Query | Lookup | Table[poly] | node(id) = by-id (judgment call) |
| QueryNodesField | Query | Lookup | Table[poly] | nodes(ids) (judgment call) |
| QueryServiceTableField | ReturnedValue.QueryService → Query.LookupResult | composite | Table | two-part (D) |
| QueryServiceRecordField | ReturnedValue | QueryService | Record | |

## Root mutation — the write side

| Leaf | provider | intent | mapping | note |
|---|---|---|---|---|
| MutationInsertTableField | ⚠ | Insert | Column (encoded) / Table (projected) | projected → + LookupResult |
| MutationUpdateTableField | ⚠ | Update | Column / Table | |
| MutationDeleteTableField | ⚠ | Delete | Column | encoded-id only (R287) |
| MutationUpsertTableField | ⚠ | Upsert | Column / Table | |
| MutationServiceTableField | ReturnedValue.MutationService → Query.LookupResult | composite | Table | two-part (D) |
| MutationServiceRecordField | ReturnedValue | MutationService | Record | |
| MutationDmlRecordField | ⚠ → ReturnedValue | DmlKind | Record | write then read RETURNING |
| MutationBulkDmlRecordField | ⚠ → ReturnedValue | DmlKind | Record | bulk |
| MutationUpdatePayloadField | ⚠ → ReturnedValue | Update | Record | |
| MutationBulkUpdatePayloadField | ⚠ → ReturnedValue | Update | Record | bulk |
| MutationDeletePayloadField | ⚠ → ReturnedValue | Delete | Record | |
| MutationBulkDeletePayloadField | ⚠ → ReturnedValue | Delete | Record | bulk |

## Child — column carriers (QueryPart)

| Leaf | provider | intent | mapping | note |
|---|---|---|---|---|
| ColumnField | QueryPart | Fetch | Column | |
| ColumnReferenceField | QueryPart | Fetch | Column | join-path |
| ParticipantColumnReferenceField | QueryPart | Fetch | Column | poly join |
| CompositeColumnField | QueryPart | Fetch | Column | composite |
| CompositeColumnReferenceField | QueryPart | Fetch | Column | composite, deferred-stub |

## Child — table targets

| Leaf | provider | intent | mapping | note |
|---|---|---|---|---|
| TableField | QueryPart | Fetch | Table | correlated multiset |
| LookupTableField | QueryPart | Lookup | Table | |
| TableInterfaceField | QueryPart | Fetch | Table[poly] | R288 emit divergence |
| TableMethodField | QueryPart | Fetch | Table | method-backed |
| SplitTableField | Query | Fetch | Table | batch-key |
| SplitLookupTableField | Query | Lookup | Table | batch-key |
| RecordTableField | Query | Fetch | Table | record-parent key |
| RecordLookupTableField | Query | Lookup | Table | record-parent key |
| RecordTableMethodField | Query | Fetch | Table | method + record-parent |
| ServiceTableField | ReturnedValue.QueryService → Query.LookupResult | composite | Table | two-part (D) |
| SingleRecordTableField | Query | LookupResult | Table | the materialized re-projection continuation |

## Child — service record / passthrough scalars

| Leaf | provider | intent | mapping | note |
|---|---|---|---|---|
| ServiceRecordField | ReturnedValue | QueryService | Record | |
| RecordField | ReturnedValue | ⚠ | Field | pure read off parent record (B) |
| PropertyField | ReturnedValue | ⚠ | Field | pure read (B) |
| ComputedField | QueryPart | Fetch | Column | @externalField inline jOOQ Field, method-backed |

## Child — nesting / constructor / polymorphic / returning

| Leaf | provider | intent | mapping | note |
|---|---|---|---|---|
| NestingField | QueryPart | Fetch | Table | structural passthrough (collides w/ TableField — C) |
| ConstructorField | ReturnedValue | ⚠ | Record | pure construction from parent record (B) |
| InterfaceField | Query | Fetch | Table[poly] | |
| UnionField | Query | Fetch | Table[poly] | |
| SingleRecordIdFieldFromReturning | ReturnedValue | ⚠ | Column | reads encoded PK off RETURNING (B) |
| SingleRecordIdField | ReturnedValue | ⚠ | Column | encodes node-key off in-mem record (B) |
| ErrorsField | ReturnedValue | ⚠ | Record | reads Outcome arm off source (B) |

## Connection protocol roles (not current leaves)

| Role | provider | intent | mapping | note |
|---|---|---|---|---|
| edges | Query | Fetch | Table | the establishing page query |
| totalCount | QueryPart | Count | Column | correlated count (emit currently standalone) |
| facets | QueryPart | Facet | Record? | grouped aggregate (mapping unclear) |
| nodes | ReturnedValue | ⚠ | Table | read off ConnectionResult (B) |
| pageInfo | ReturnedValue | ⚠ | Record | read off ConnectionResult (B) |

---

## Mechanical tests

**1. Totality (every leaf gets a verdict): FAILS, on two fronts.**
- (A) Every write leaf (12 mutation rows) has `⚠` in the provider column. `{QueryPart, Query, ReturnedValue}` has no value for "the resolver executed a write statement." A DML is the resolver's own SQL (so not `ReturnedValue`), but it is not a query (so not `Query`).
- (B) Every pure read off an in-hand value (`RecordField`, `PropertyField`, `ConstructorField`, `ErrorsField`, `SingleRecordIdField`, `SingleRecordIdFieldFromReturning`, + `nodes`/`pageInfo` roles) has `⚠` in the intent column. The intent set is query-side; reading a property off a returned record performs no operation.

**2. Distinguishing (verdict carries enough to drive emit): FALSE as stated, by design.**
The triple is not a unique key. Collisions with different codegen:
- `TableField` vs `NestingField` (both `QueryPart.Fetch/Table`): multiset vs structural passthrough.
- `QueryTableField` vs `QueryTableMethodTableField` (both `Query.Fetch/Table`): generated vs method-backed.
- `SplitTableField` vs `RecordTableField` vs `RecordTableMethodField` (all `Query.Fetch/Table`): distinguished only by batch-key/method slots.
These resolve through the orthogonal slots (method-backed, batch-key, inherits-context), which is consistent with R281, but it means the three axes are the *classification*, not the full emit-determinant. Worth stating outright rather than implying the triple is a key.

**3. Every axis value is used: mostly, with the known gaps.**
- provider: `QueryPart`, `Query`, `ReturnedValue` all used. Write provider (if added) used by 12 leaves.
- intent: `Fetch`, `Lookup`, `LookupResult`, `Insert`/`Update`/`Delete`/`Upsert`, `QueryService`/`MutationService` all used. `Count`/`Facet` used only by connection roles (not current leaves) — the declared R299 gaps, confirmed.
- mapping: `Table`, `Column`, `Record`, `Field` all used.

**4. Illegal combos unspellable (provider gates intent): holds where the providers exist.**
- `QueryPart`/`Query` (SQL) → read intents only. ✓
- `ReturnedValue` → `{QueryService, MutationService}` for service-backed, but pure reads want a value it does not have (B again).
- `LookupResult` correctly appears only on the service-requery continuation and `SingleRecordTableField`. ✓
- Write intents have nowhere legal to sit until a write provider exists (A).

---

## What the table says about our ideas

- **`QueryPart` earns its place.** Every inline catalog read (columns, multiset, computed, nesting,
  totalCount) sits cleanly under it, including the bare column that broke `SubQuery`/`CorrelatedQuery`.
  No `⚠` on the read-via-SQL side.
- **`Query` and `ReturnedValue` hold** for standalone reads and service returns respectively.
- **The triad does not close over writes (A)** or over pure in-hand reads (B). These are the two real
  holes, and they are structural, not naming.
- **The service-requery composite (D)** is genuinely two-part (`ReturnedValue.<polarity>` →
  `Query.LookupResult`); 4 leaves need a composite verdict or a collapse decision. This is R290's
  central case.

### Candidate resolutions (to argue about, not adopt)

- **A — write provider.** Either add a fourth provider (`Statement`? `Mutation`? `Write`?) for
  resolver-run writes, or broaden `Query` from "SELECT statement" to "resolver-run statement"
  covering DML. The latter keeps three providers but makes `Query` mean "any statement the resolver
  issues," with the read/write split living entirely in intent.
- **B — pure-read intent.** Either add a degenerate `Read`/`Project` intent for `ReturnedValue`
  reads, or declare intent **not total** — it is a property of query/service/write operations, and a
  bare projection off an in-hand value simply has none. (This reopens the totality question R299
  just closed for intent; the honest answer may be "intent is total over *operations*, and a
  ReturnedValue pure-read is not an operation.")
- **D — composite verdict.** Either the verdict for service-requery leaves is an explicit
  two-step pipeline, or `LookupResult` absorbs the upstream polarity into one value
  (`QueryServiceLookup` / `MutationServiceLookup`), trading a clean intent enum for a flat verdict.
