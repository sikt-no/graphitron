---
id: R1089
title: "A bulk DELETE with an id that matches no row rolls back the whole call, where Graphitron 9 deleted the rest"
status: Backlog
bucket: enhancement
priority: 3
theme: mutation-write
depends-on: []
created: 2026-10-22
last-updated: 2026-10-22
---

# A bulk DELETE with an id that matches no row rolls back the whole call, where Graphitron 9 deleted the rest

## Goal

A consumer should be able to declare that a list-input `@mutation(typeName: DELETE)` treats an id that matches no row as already deleted, as Graphitron 9 did. In that mode the call deletes the rows that exist, commits, and answers the payload with every input id, with no error. Today the per-row guard in `TypeFetcherGenerator.buildBulkRecordPerRowDeleteBody` throws "matched zero rows" and the whole call rolls back. With no handler for that exception, the client gets the redacted "An error occurred. Reference: …". Graphitron 10's behaviour stays the default. The new behaviour is opt-in per field.

## Why

Reported by sis, whose Graphitron 9 deletes did not check row counts (9 failed only UPDATE and UPSERT on zero rows). Affected: stable `slettTimeplan` and `slettTimeplanroller`, beta `slettPersonbilder` and `trekkVurderingsmeldinger`, and 23 experimental deletes. None of them has a type for the zero-row case in its errors union. Two reasons to keep 9's behaviour:

- **Idempotency.** A client that retries a partly applied batch, or two clients that delete the same row, should not have the whole call rolled back because a row is already gone. After the call the id does not exist, which is what the client asked for.
- **No existence signal.** sis's developers do not want a delete to tell the caller whether a row with that key existed. Tenant routing already refuses an id from another institution before any write, so what is left is whether a row visible and writable to the caller exists. Only echoing every input id hides that. Leaving the missing ids out of the payload leaks the same bit as the error does.

## Sketch (fill in at Spec)

- **Where to declare it**: an argument on `@mutation` (for example `onMissingRow: FAIL | IGNORE`), or a plugin-wide default. Per field fits better, since UPDATE keeps failing and a consumer may want both kinds of DELETE.
- **The echo**: the payload today comes from the `RETURNING` primary key of each deleted row. In the ignore mode, a missing row's slot is filled from the decoded input key, so the payload keeps one entry per input row, in input order.
- **Single-row DELETE**: the Spec records what a single-row DELETE does today with a key that matches no row, and whether the ignore mode applies there too.
- **Logging**: none for a missing row in the ignore mode. It is not an error.
