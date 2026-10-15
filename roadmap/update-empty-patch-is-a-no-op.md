---
id: R1040
title: "A DML UPDATE row that sends only its key fields is a no-op that still returns the row"
status: Backlog
bucket: bug
priority: 3
theme: mutation-write
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# A DML UPDATE row that sends only its key fields is a no-op that still returns the row

## Goal

A `@mutation(typeName: UPDATE)` row that carries only its key fields issues no UPDATE for that row and still appears in the payload exactly as an updated row would: re-read by key, under the same tenant routing, in the same position in the batch, while the other rows in the batch update normally. Today the generated runtime PATCH guard throws `IllegalArgumentException("@mutation(typeName: UPDATE) call has no settable fields present; only key fields were provided")` when the row's SET map is empty. The throw happens inside the transaction, so it rolls back the whole batch, and no payload error type maps it, so the client gets the redacted "An error occurred. Reference: <uuid>" (the R1036 shape). Clients use a "send only what changed" PATCH contract, so a patch with nothing in it is a legitimate request, not a malformed one: a UI that always submits the id plus whatever the user touched sends one whenever the user saves without editing. Graphitron 9 handled this by going through jOOQ `store()` with changed flags, which issues no statement for an unchanged record and echoes the row. The build-time guarantee that the input type has at least one settable column stays as it is.

## Evidence

Reported from the sis port on 2026-10-15 against trunk `e4cfef7c1`. sis's generated `MutationFetchers` carries 60 of these guards. Example: `endreStudieprogramOkonomi(input: [EndreStudieprogramOkonomiInput!]!)` with `@mutation(typeName: UPDATE, table: "studieprogram")`, sent `[{id: "<existing id>"}]`. sis expects no top-level errors, a null payload `errors`, the id echoed in `studieprogram`, and the row unchanged in the database. Six sis integration tests fail on the guard, all `EndreStudieprogram*MutationIT` cases that leave every optional field out (`..._ingenFelterSatt_...`, `..._beggeFeltgrupperUtelatt_...`, `..._utenFelt_...`, `..._alleFelterUtelatt_...`).

The guard is emitted by `TypeFetcherGenerator` at several sites, each with its own message: the per-row UPDATE, the bulk UPDATE ("bulk call has no settable fields present in the input rows"), the UPSERT update branch, and further UPDATE / UPSERT copies further down the same class. `FetcherPipelineTest` asserts the UPDATE message today. The guard came in with the dynamic-SET UPDATE work (`36a4e2db9`).

## Questions for the Spec

- **An empty patch on a key that matches no row.** A non-empty UPDATE that matches zero rows throws `IllegalStateException("... matched zero rows ...")`, which sis maps to its "not found" payload error (`FantIngenOppforingAaEndre`). An empty patch should give the same outcome rather than quietly returning no row, so that a no-op and a missing row differ by more than the absence of an element in the payload. The skip path therefore still owes an existence check by key, under the same tenant routing.
- **Bulk UPDATE.** The bulk arm builds one statement over many rows, and today it throws when no row carries a settable field. Whether rows that carry nothing are dropped from the statement and only existence-checked, and how a mix of empty and non-empty rows is ordered in the payload, is the Spec's to settle.
- **UPSERT.** An empty patch on the UPSERT update branch plausibly means "insert if absent, otherwise leave the row as it is" (`ON CONFLICT DO NOTHING` plus a re-read), but that is a separate contract from UPDATE and needs its own decision rather than being carried along.
