---
id: R141
title: "Admit bulk-input mutations with a single payload carrier wrapping a list-shaped data field"
status: Backlog
bucket: feature
priority: 2
theme: mutations-errors
depends-on: [mutation-bulk-input-single-payload-key-loss]
---

# Admit bulk-input mutations with a single payload carrier wrapping a list-shaped data field

The shape

```graphql
extend type Mutation {
    opprettKvotesporsmalPreutfylling(input: [OpprettKvotesporsmalPreutfyllingInput]): KvotesporsmalPreutfyllingPayload! @mutation(typeName: INSERT)
}
type KvotesporsmalPreutfyllingPayload { kvotesporsmalPreutfylling: [KvotesporsmalPreutfylling!] }
input OpprettKvotesporsmalPreutfyllingInput @table(name: "kvotesporsmal_preutfylling") { kvotesporsmalPreutfyllingKode: String! @field(name: "KVOTESPORSMAL_PREUTFYLLING_KODE") }
```

— bulk `@table` input, single payload carrier, list-shaped data field inside the carrier — is the **main shape consumers want from a mutation payload**. The carrier is the natural seam for sibling fields the platform expects to add over time (`errors`, `affectedRowCount`, `clientMutationId`, etc.) alongside the list-shaped read-back of the rows the mutation touched. Both the `bulk → bulk list` and the `bulk → singleton domain type` shapes already admitted by R75/R138 are special cases that don't generalise to the "result envelope with several fields" pattern this item enables.

R138 (In Review at the time of filing) rejects this shape at classify time with an Invariant #15 message that names `TooManyRowsException` as the runtime mechanism. That mechanism is real for the *singleton* carrier arm R138 closes (`Payload { film: Film }`, single-cardinality data field), but it does not apply here: the data field is list-shaped (`kvotesporsmalPreutfylling: [KvotesporsmalPreutfylling!]`), so N input rows have an honest place to land — one carrier, N rows inside it. R138's "Defers" clause names exactly this slug as the follow-up.

Admitting the shape needs (sketch — to be expanded in Spec):

- a new sealed leaf in the mutation-field hierarchy (working name `MutationBulkDmlRecordField`) with the same `@table` bulk-input contract as `MutationDmlRecordField` but a list-cardinality data field;
- a carrier-resolution rule that pairs `listInput == true` with `dataField.wrapper().isList() == true` (the converse of R138's per-arm check), routed before the lifted Invariant #15 predicate so the bulk + list-data-field case is admitted rather than rejected;
- an emit shape based on `.fetch()` (not `.fetchOne()`) returning a `Result<RecordN<...>>` keyed back to the input rows by the table's PK columns, then mapped onto the data field's element type;
- response-SELECT predicate `PK in (source.stream().map(row -> row.<pkField>).toList())` for the read-back arm, mirroring the singleton-carrier read-back path R75 Phase 1 introduced but list-shaped;
- truth-table coverage at the classifier tier mirroring R138's `DML_INSERT_LIST_PLAIN_PAYLOAD_REJECTED` row with an admitted counterpart, plus an execution-tier test that round-trips >1 input rows through the carrier and asserts all N appear in the read-back list (the latter is meaningful here, unlike R138, because the emit path is `.fetch()` and the assertion exercises real row-to-input correlation).

Sibling fields on the payload (`errors`, `affectedRowCount`, etc.) are out of scope for this item; the carrier-with-list-data-field admission is the load-bearing change, and the sibling-field surface is a separate planning question (likely an `@mutationField(kind: ...)` directive on the carrier's non-data fields). This item should not block on that surface being designed.

Depends on R138 landing first, because the lifted Invariant #15 predicate this item must thread around is the predicate R138 introduces.
