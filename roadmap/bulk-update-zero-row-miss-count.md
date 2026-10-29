---
id: R1123
title: "The bulk UPDATE zero-row guard reports how many input rows matched nothing"
status: Backlog
bucket: feature
priority: 5
theme: mutation-write
depends-on: []
created: 2026-10-29
last-updated: 2026-10-29
---

# The bulk UPDATE zero-row guard reports how many input rows matched nothing

## Goal

When a bulk `@mutation(typeName: UPDATE)` has input rows that match no row, its zero-row guard runs every input row's statement first and then fails once, reporting how many of the input rows matched nothing. Today it fails on the first miss and names only that row's index, so a `FetcherErrorResponse` (the consumer's hook for the client-facing text of a fetcher exception) cannot tell a client "2 of 5 rows were not found". The call still rolls back as it does today, so the statements that run after the first miss write nothing.

## The case from sis

48 sis UPDATE mutations have no `@error` handler for the zero-row guard, so the client gets `FetcherErrorResponse.unmatchedMessage`. sis wants to return Graphitron 9's text from its implementation of that hook (`FsFeilsvar.unmatchedMessage`): "N of M statements in this write affected no rows. The rows may not exist, or may not be writable for the current user." Graphitron 9 threw this as `NoRowsAffectedException` from `QueryHelper` in graphitron-common, after every statement had run. M is the number of input rows, which sis reads from `env.getArgument("input")`. N is the number of rows that matched nothing, and nothing outside the generated fetcher can know it today, so sis ships "1 of M" in the meantime.

## Where it happens today

`TypeFetcherGenerator.buildCarrierBulkPerRowUpdateBody` emits the per-row body that `buildMutationBulkDmlRecordFetcher` loops over inside one `transactionResult`. Each row runs one `UPDATE … RETURNING` (or the key read, for an empty patch) through `fetchOne()`. On a `null` record it throws:

```
IllegalStateException("@mutation(typeName: UPDATE) bulk row matched zero rows; key filter found no target for input row at index <i> of <Type.field>")
```

The index is `acc.size()`, which holds because rows are appended in order and the loop stops at the first miss. The javadoc ties this fail-fast to the order-preservation invariant: `acc` never skews against `in`. The message names the row by index and the field by coordinate, never by input content, so client data stays out of logs.

## Proposed shape (from the sis request)

* Run every input row's statement, count the misses, and throw once after the loop if the count is non-zero, carrying the miss count and the input total.
* A stable message shape is enough for sis, for example "… matched zero rows for 2 of 5 input rows …". A typed exception with accessors is equally fine. The choice is Spec's.
* **Keep the substring "matched zero rows".** sis's `FantIngenOppforingAaEndre` handlers (12 unions) and `SanertFeillogg` match on it.

## Open questions for Spec

* **Message or typed exception.** A message shape is a contract consumers parse. A typed exception (or `IllegalStateException` subclass, to keep existing `@error` handlers matching) with `missCount()` / `rowCount()` accessors is the sturdier contract. The indices of the missed rows could ride along, since an index carries no input content.
* **The order-preservation invariant.** With the loop running on past a miss, `acc` no longer matches `in` position for position. That is harmless only because the call then throws and rolls back. The new guard should keep that true by construction, so that no path returns a skewed `acc`.
* **Cost.** Running the remaining statements costs a round trip per row, on a call that is going to roll back. That is what Graphitron 9 did. The cost is bounded by the input size.
* **Other UPDATE shapes.** The PostgreSQL-only VALUES-join bulk UPDATE (`KeyStatement.ValuesJoinUpdate`, for a listed `@table` input) runs as one statement. Check whether it has a zero-row guard at all, and whether it should report the same count.

## Scope

UPDATE only. The DELETE guard ("bulk row matched zero rows; @lookupKey filter found no target …") is a separate sis row (C34) and not part of this item.

## Provenance

Requested 2026-10-29 by the sis Graphitron 10 port session, which tracks it as C33 in `GRAPHITRON10_STATUS.md`. Low priority: sis ships "1 of M" meanwhile.
